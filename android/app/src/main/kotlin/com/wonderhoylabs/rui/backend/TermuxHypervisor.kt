package com.wonderhoylabs.rui.backend

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.wonderhoylabs.rui.model.BackendKind
import com.wonderhoylabs.rui.model.VmConfig
import com.wonderhoylabs.rui.terminal.Ssh
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket

/** A Linux userland Termux's proot-distro can install, and how to put an SSH server in it. */
data class TermuxDistro(
	val id: String,
	val name: String,
	val installSsh: String,
)

/**
 * Machines that are proot-distro userlands in Termux, for phones without AVF. They share the phone's kernel and
 * network: nothing is virtualised, and the list says "Termux", never "VM". Rui drives Termux through its
 * documented `RUN_COMMAND` intent, which the user allows by setting `allow-external-apps = true` in
 * `~/.termux/termux.properties`. The console is SSH to an sshd inside the userland on the phone's loopback.
 */
class TermuxHypervisor(
	private val context: Context,
) : Hypervisor {
	override val kind = BackendKind.TERMUX

	override fun availability(): Availability {
		val installed = runCatching { context.packageManager.getPackageInfo(TERMUX, 0) }.isSuccess
		return when {
			!installed -> Availability.Unavailable("Termux isn't installed. Install it from F-Droid or its GitHub releases.")
			context.checkSelfPermission(RUN_COMMAND_PERMISSION) != PackageManager.PERMISSION_GRANTED ->
				Availability.Unavailable("Rui needs permission to run commands in Termux.")
			else -> Availability.Ready
		}
	}

	/** The port a machine's sshd listens on, the same every time. */
	fun port(vm: VmConfig): Int = PORT_BASE + (vm.id.hashCode() and 0x7fffffff) % PORT_RANGE

	override fun start(
		vm: VmConfig,
		dir: File,
		events: GuestEvents,
	): RunningGuest {
		val distro = DISTROS.firstOrNull { it.id == vm.distro } ?: throw IOException("Pick a distribution for this Termux machine.")
		val password = vm.access.password ?: throw IOException("This machine has no password set.")
		val port = port(vm)
		val firstRun = !File(dir, SET_UP_MARKER).exists()
		run(script(distro, port, password), background = !firstRun)
		val deadline = System.currentTimeMillis() + if (firstRun) SETUP_TIMEOUT_MS else START_TIMEOUT_MS
		while (!listening(port)) {
			if (System.currentTimeMillis() > deadline) {
				throw IOException(
					if (firstRun) "Termux is still setting up ${distro.name}. Watch it in Termux, then start again." else "The machine's SSH server didn't start in Termux.",
				)
			}
			Thread.sleep(POLL_MS)
		}
		File(dir, SET_UP_MARKER).createNewFile()
		val shell =
			Ssh.channel(
				hostAlias = "termux-${vm.id}",
				user = "root",
				password = password,
				privateKey = null,
				knownHosts = File(dir, "ssh/known_hosts"),
				open = { DemoHypervisor.tcp("127.0.0.1:$port") },
				// Rui made this server a moment ago on the phone's own loopback, so its key is trusted on first use.
				confirmHostKey = { true },
			)
		return object : RunningGuest {
			override val console =
				object : ConsoleChannel {
					override val output: InputStream = shell.output
					override val input = shell.input

					override fun resize(
						rows: Int,
						cols: Int,
					) = shell.resize(rows, cols)

					override fun close() = shell.close()
				}
			override val log: InputStream? = null

			override fun connect(guestPort: Int): GuestConnection = DemoHypervisor.tcp("127.0.0.1:$guestPort")

			override fun listeningPorts(): List<Int>? = null

			override fun requestShutdown(): Boolean {
				forceStop()
				return true
			}

			override fun forceStop() {
				shell.close()
				runCatching { run("pkill -f 'sshd -D -p $port' || true", background = true) }
				events.onStopped(null)
			}
		}
	}

	private fun listening(port: Int): Boolean =
		runCatching {
			Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 500) }
			true
		}.getOrDefault(false)

	private fun script(
		distro: TermuxDistro,
		port: Int,
		password: String,
	): String {
		val inside =
			"${distro.installSsh} && mkdir -p /run/sshd && ssh-keygen -A && echo 'root:$password' | chpasswd && " +
				"exec /usr/sbin/sshd -D -p $port -o PermitRootLogin=yes -o PasswordAuthentication=yes -o ListenAddress=127.0.0.1"
		return "set -e; command -v proot-distro >/dev/null || pkg install -y proot-distro; " +
			"proot-distro login ${distro.id} -- true 2>/dev/null || proot-distro install ${distro.id}; " +
			"proot-distro login ${distro.id} -- sh -c \"$inside\""
	}

	private fun run(
		script: String,
		background: Boolean,
	) {
		val intent =
			Intent()
				.setClassName(TERMUX, "com.termux.app.RunCommandService")
				.setAction("com.termux.RUN_COMMAND")
				.putExtra("com.termux.RUN_COMMAND_PATH", "/data/data/com.termux/files/usr/bin/bash")
				.putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("-c", script))
				.putExtra("com.termux.RUN_COMMAND_WORKDIR", "/data/data/com.termux/files/home")
				.putExtra("com.termux.RUN_COMMAND_BACKGROUND", background)
				.putExtra("com.termux.RUN_COMMAND_SESSION_ACTION", "0")
		try {
			context.startForegroundService(intent)
		} catch (error: SecurityException) {
			throw IOException("Termux refused. Set allow-external-apps = true in ~/.termux/termux.properties.", error)
		}
	}

	companion object {
		const val TERMUX = "com.termux"
		const val RUN_COMMAND_PERMISSION = "com.termux.permission.RUN_COMMAND"
		private const val PORT_BASE = 20000
		private const val PORT_RANGE = 10000
		private const val POLL_MS = 1000L
		private const val START_TIMEOUT_MS = 30_000L
		private const val SETUP_TIMEOUT_MS = 15 * 60_000L
		private const val SET_UP_MARKER = "termux-set-up"

		val DISTROS =
			listOf(
				TermuxDistro("debian", "Debian", "(command -v sshd >/dev/null || (apt-get update && apt-get install -y openssh-server))"),
				TermuxDistro("ubuntu", "Ubuntu", "(command -v sshd >/dev/null || (apt-get update && apt-get install -y openssh-server))"),
				TermuxDistro("archlinux", "Arch Linux", "(command -v sshd >/dev/null || pacman -Sy --noconfirm openssh)"),
				TermuxDistro("alpine", "Alpine", "(command -v sshd >/dev/null || apk add openssh)"),
				TermuxDistro("fedora", "Fedora", "(command -v sshd >/dev/null || dnf install -y openssh-server)"),
			)
	}
}
