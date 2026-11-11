package com.wonderhoylabs.minori.backend

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import com.wonderhoylabs.minori.model.BackendKind
import com.wonderhoylabs.minori.model.VmConfig
import com.wonderhoylabs.minori.service.TermuxDesktop
import com.wonderhoylabs.minori.terminal.Ssh
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * A Linux userland Termux's proot-distro can install, and how to put an SSH server in it. [airiLibraries] installs what
 * Airi's agent needs (glibc, pixman and xkbcommon); it's null where the agent can't run, as on Alpine's musl.
 */
data class TermuxDistro(
	val id: String,
	val name: String,
	val installSsh: String,
	val airiLibraries: String? = null,
	/** Why proot-distro can't install it on this phone, or null when it can. */
	val unavailable: String? = null,
	/** How Termux installs it, if not from proot-distro's own image of the same name. */
	val install: String = "proot-distro install $id",
)

/**
 * Machines that are proot-distro userlands in Termux, for phones without AVF. They share the phone's kernel and
 * network: nothing is virtualised, and the list says "Termux", never "VM". Minori drives Termux through its
 * documented `RUN_COMMAND` intent, which the user allows by setting `allow-external-apps = true` in
 * `~/.termux/termux.properties`. The console is SSH to an sshd inside the userland on the phone's loopback.
 */
class TermuxHypervisor(private val context: Context) : Hypervisor {
	override val kind = BackendKind.TERMUX

	override fun availability(): Availability {
		val installed = runCatching { context.packageManager.getPackageInfo(TERMUX, 0) }.isSuccess
		return when {
			!installed -> Availability.Unavailable("Termux isn't installed. Install it from F-Droid or its GitHub releases.")

			!offersRunCommand(context) -> Availability.Unavailable(PLAY_BUILD_REASON)

			context.checkSelfPermission(RUN_COMMAND_PERMISSION) != PackageManager.PERMISSION_GRANTED ->
				Availability.Unavailable("Minori needs permission to run commands in Termux.")

			else -> Availability.Ready
		}
	}

	/** The port a machine's sshd listens on, the same every time. */
	fun port(vm: VmConfig): Int = PORT_BASE + (vm.id.hashCode() and 0x7fffffff) % PORT_RANGE

	override fun start(vm: VmConfig, dir: File, events: GuestEvents): RunningGuest {
		val distro =
			DISTROS.firstOrNull { it.id == vm.distro } ?: throw IOException("Pick a distribution for this Termux machine.")
		distro.unavailable?.let { throw IOException(it) }
		val password = vm.access.password ?: throw IOException("This machine has no password set.")
		val port = port(vm)
		val firstRun = !File(dir, SET_UP_MARKER).exists()
		// Termux answers through a PendingIntent when it refuses a command or the command ends. sshd -D doesn't end,
		// so any answer before the port opens means something went wrong, and it says what.
		val desktop = if (TermuxDesktop.isSetUp(vm)) vncPort(vm) to rdpPort(vm) else null
		// Always in the background: Termux only opens a visible session from the background with "Display over other
		// apps", and Termux's notification shows the command running either way.
		val ended = run(script(distro, port, password, if (vm.airi) airiPort(vm) else null, desktop), background = true)
		val deadline = System.currentTimeMillis() + if (firstRun) SETUP_TIMEOUT_MS else START_TIMEOUT_MS
		while (!listening(port)) {
			ended.getNow(null)?.let { throw IOException(it.explain(distro)) }
			if (System.currentTimeMillis() > deadline) {
				throw IOException(
					if (firstRun) {
						"Termux is still setting up ${distro.name}. Give it a few more minutes, then start again."
					} else {
						"The machine's SSH server didn't start in Termux."
					},
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
				// Minori made this server a moment ago on the phone's own loopback, so its key is trusted on first use.
				confirmHostKey = { true },
			)
		return object : RunningGuest {
			override val console =
				object : ConsoleChannel {
					override val output: InputStream = shell.output
					override val input = shell.input

					override fun resize(rows: Int, cols: Int) = shell.resize(rows, cols)

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
				// Airi's agent and the loop that restarts it go with the machine, or they'd go on running in Termux.
				val airi = airiPort(vm)
				val stop =
					"pkill -f 'sshd -D -p $port'; pkill -f 'minori-airi $airi '; " +
						"pkill -f 'airi-agent serve --port $airi'; " +
						// The desktop loop notices the SSH server has gone and stops its servers itself.
						"true"
				runCatching { run(stop, background = true) }
				events.onStopped(null)
			}
		}
	}

	private fun listening(port: Int): Boolean = runCatching {
		Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 500) }
		true
	}.getOrDefault(false)

	private fun script(
		distro: TermuxDistro,
		port: Int,
		password: String,
		airiPort: Int?,
		desktop: Pair<Int, Int>?,
	): String {
		// Airi's agent, once Minori has set it up here, runs beside the SSH server: see AiriSetup.
		val airi = airiPort?.let { "(test ! -x $AIRI_LOOP || (nohup $AIRI_LOOP $it $port >/dev/null 2>&1 &)) && " }.orEmpty()
		// So does the desktop, once Minori has added one: see TermuxDesktop.
		val loop = TermuxDesktop.LOOP
		val desktopLoop =
			desktop
				?.let { (vnc, rdp) -> "(test ! -x $loop || (nohup $loop $vnc $rdp $port >/dev/null 2>&1 &)) && " }
				.orEmpty()
		val inside =
			"${distro.installSsh} && mkdir -p /run/sshd && ssh-keygen -A && echo 'root:$password' | chpasswd && " +
				airi + desktopLoop +
				"exec /usr/sbin/sshd -D -p $port -o PermitRootLogin=yes -o PasswordAuthentication=yes -o ListenAddress=127.0.0.1"
		return "set -e; command -v proot-distro >/dev/null || pkg install -y proot-distro; " +
			"proot-distro login ${distro.id} -- true 2>/dev/null || (${distro.install}); " +
			"proot-distro login ${distro.id} -- sh -c \"$inside\""
	}

	/**
	 * Checks that Termux will run Minori's commands at all, by running `true`. Returns null when it does, or what
	 * the user has to do.
	 */
	fun check(): String? {
		val result =
			try {
				run("true", background = true).get(CHECK_TIMEOUT_MS, TimeUnit.MILLISECONDS)
			} catch (error: IOException) {
				return error.message
			} catch (_: java.util.concurrent.TimeoutException) {
				return "Termux didn't answer. Open Termux once so it can finish installing, then try again."
			}
		return if (result.ok) null else result.explain(null)
	}

	private fun run(script: String, background: Boolean): CompletableFuture<TermuxResult> {
		val id = nextId.incrementAndGet()
		val answer = CompletableFuture<TermuxResult>()
		pending[id] = answer
		val reply =
			PendingIntent.getBroadcast(
				context,
				id,
				Intent(context, TermuxResultReceiver::class.java).putExtra(TermuxResultReceiver.EXTRA_ID, id),
				PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_MUTABLE,
			)
		val intent =
			Intent()
				.setClassName(TERMUX, "com.termux.app.RunCommandService")
				.setAction("com.termux.RUN_COMMAND")
				.putExtra("com.termux.RUN_COMMAND_PATH", "/data/data/com.termux/files/usr/bin/bash")
				.putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("-c", script))
				.putExtra("com.termux.RUN_COMMAND_WORKDIR", "/data/data/com.termux/files/home")
				.putExtra("com.termux.RUN_COMMAND_BACKGROUND", background)
				.putExtra("com.termux.RUN_COMMAND_SESSION_ACTION", "0")
				.putExtra("com.termux.RUN_COMMAND_PENDING_INTENT", reply)
		try {
			context.startForegroundService(intent)
		} catch (error: SecurityException) {
			pending.remove(id)
			throw IOException("Android didn't let Minori use Termux. Allow it in Settings, then try again.", error)
		}
		return answer
	}

	companion object {
		private val nextId = AtomicInteger((System.currentTimeMillis() % 100_000).toInt())
		private val pending = ConcurrentHashMap<Int, CompletableFuture<TermuxResult>>()

		/** Hands Termux's answer to whoever is waiting for it. */
		internal fun deliver(id: Int, result: TermuxResult) {
			pending.remove(id)?.complete(result)
		}

		const val TERMUX = "com.termux"
		const val RUN_COMMAND_PERMISSION = "com.termux.permission.RUN_COMMAND"

		/** Where to get the Termux that has `RUN_COMMAND`. */
		const val FDROID_URL = "https://f-droid.org/packages/com.termux/"

		/** Why Google Play's Termux can't be used, said once for every screen that needs it. */
		const val PLAY_BUILD_REASON =
			"This Termux is the Google Play version, which doesn't let other apps run commands in it. Minori needs " +
				"Termux from F-Droid or its GitHub releases."

		/**
		 * Whether the installed Termux lets other apps run commands at all. The F-Droid and GitHub builds declare
		 * `RUN_COMMAND`; Google Play's build is a different app that doesn't, and Android silently skips a request
		 * for a permission no app declares.
		 */
		fun offersRunCommand(context: Context): Boolean =
			runCatching { context.packageManager.getPermissionInfo(RUN_COMMAND_PERMISSION, 0) }.isSuccess
		private const val PORT_BASE = 20000
		private const val AIRI_PORT_BASE = 30000
		private const val VNC_PORT_BASE = 40000
		private const val RDP_PORT_BASE = 50000

		/** Where Minori puts the loop that keeps Airi's agent running in a Termux machine, which has no systemd. */
		const val AIRI_LOOP = "/root/.local/bin/minori-airi"
		private const val PORT_RANGE = 10000
		private const val POLL_MS = 1000L
		private const val START_TIMEOUT_MS = 30_000L
		private const val SETUP_TIMEOUT_MS = 15 * 60_000L
		private const val SET_UP_MARKER = "termux-set-up"
		private const val CHECK_TIMEOUT_MS = 8_000L

		/**
		 * proot-distro's own Arch comes from Docker Hub's archlinux image, which is x86-64 only, so Arch comes from Arch
		 * Linux ARM's aarch64 root filesystem instead. Its site has no HTTPS, so the tarball has to carry a good
		 * signature from the Arch Linux ARM Build System key, whose fingerprint archlinuxarm.org publishes over HTTPS. gpg
		 * comes with its libgcrypt, since a Termux that updated one and not the other has a gpg that won't start.
		 */
		private const val ARCH_KEY = "68B3537F39A313B3E574D06777193F152BDBE6A6"
		private const val ARCH_TARBALL = "ArchLinuxARM-aarch64-latest.tar.gz"
		private const val ARCH_INSTALL =
			"set +e; cd \"\$TMPDIR\" && pkg install -y gnupg libgcrypt && " +
				"curl -fL -o $ARCH_TARBALL http://os.archlinuxarm.org/os/$ARCH_TARBALL && " +
				"curl -fL -o $ARCH_TARBALL.sig http://os.archlinuxarm.org/os/$ARCH_TARBALL.sig && " +
				"export GNUPGHOME=\"\$(mktemp -d)\" && " +
				"curl -fsS 'https://keyserver.ubuntu.com/pks/lookup?op=get&search=0x$ARCH_KEY' | gpg -q --import && " +
				"gpg --status-fd 1 --verify $ARCH_TARBALL.sig $ARCH_TARBALL | grep -q 'VALIDSIG $ARCH_KEY' && " +
				"proot-distro install ./$ARCH_TARBALL --name archlinux; " +
				"status=\$?; rm -rf $ARCH_TARBALL $ARCH_TARBALL.sig \"\$GNUPGHOME\"; exit \$status"

		private const val APT_AIRI = "apt-get update && apt-get install -y libpixman-1-0 libxkbcommon0 xkb-data"

		/**
		 * The port Airi's agent listens on in a Termux machine. Termux shares the phone's loopback, so each machine needs
		 * its own, apart from the 47821 that Android's terminal forwards.
		 */
		fun airiPort(vm: VmConfig): Int = AIRI_PORT_BASE + (vm.id.hashCode() and 0x7fffffff) % PORT_RANGE

		/** The port a Termux machine's desktop listens on for VNC, on the phone's loopback. */
		fun vncPort(vm: VmConfig): Int = VNC_PORT_BASE + (vm.id.hashCode() and 0x7fffffff) % PORT_RANGE

		/** The port a Termux machine's desktop listens on for Remote Desktop, on the phone's loopback. */
		fun rdpPort(vm: VmConfig): Int = RDP_PORT_BASE + (vm.id.hashCode() and 0x7fffffff) % PORT_RANGE

		val DISTROS =
			listOf(
				TermuxDistro(
					"debian",
					"Debian",
					"(command -v sshd >/dev/null || (apt-get update && apt-get install -y openssh-server))",
					APT_AIRI,
				),
				TermuxDistro(
					"ubuntu",
					"Ubuntu",
					"(command -v sshd >/dev/null || (apt-get update && apt-get install -y openssh-server))",
					APT_AIRI,
				),
				TermuxDistro(
					"archlinux",
					"Arch Linux",
					// pacman sandboxes itself with Landlock, which proot can't give it, and its keyring starts empty.
					"(command -v sshd >/dev/null || ((grep -q '^DisableSandbox' /etc/pacman.conf || " +
						"sed -i '/^\\[options\\]/a DisableSandbox' /etc/pacman.conf) && " +
						"(test -s /etc/pacman.d/gnupg/pubring.gpg || (pacman-key --init && pacman-key --populate archlinuxarm)) && " +
						"pacman -Sy --noconfirm openssh))",
					"pacman -Sy --noconfirm --needed pixman libxkbcommon xkeyboard-config",
					install = ARCH_INSTALL,
				),
				TermuxDistro("alpine", "Alpine", "(command -v sshd >/dev/null || apk add openssh)"),
				TermuxDistro(
					"fedora",
					"Fedora",
					"(command -v sshd >/dev/null || dnf install -y openssh-server)",
					"dnf install -y pixman libxkbcommon xkeyboard-config",
				),
			)
	}
}

/** What Termux said about a command: refused, or ended with an exit code and what it printed. */
data class TermuxResult(
	val err: Int,
	val errmsg: String?,
	val exitCode: Int?,
	val stdout: String,
	val stderr: String,
) {
	/** Termux's own code for "no error" is -1. */
	val ok: Boolean get() = (err == -1 || err == 0) && (exitCode ?: 0) == 0

	fun explain(distro: TermuxDistro?): String {
		val message = errmsg.orEmpty()
		return when {
			"allow-external-apps" in message ->
				"Termux doesn't let other apps run commands yet. In Termux, run: mkdir -p ~/.termux && " +
					"echo 'allow-external-apps = true' >> ~/.termux/termux.properties"

			err != -1 && err != 0 && message.isNotBlank() -> "Termux refused: ${message.lines().first()}"

			else -> {
				val output = (stderr.ifBlank { stdout }).trim().lines().takeLast(4).joinToString("\n")
				val what = distro?.let { "Setting up ${it.name} in Termux" } ?: "The command in Termux"
				"$what stopped (exit code ${exitCode ?: "unknown"})." + if (output.isNotEmpty()) "\n$output" else ""
			}
		}
	}

	companion object {
		fun from(bundle: Bundle?): TermuxResult = TermuxResult(
			err = bundle?.getInt("err", -1) ?: -1,
			errmsg = bundle?.getString("errmsg"),
			exitCode = bundle?.takeIf { it.containsKey("exitCode") }?.getInt("exitCode"),
			stdout = bundle?.getString("stdout").orEmpty(),
			stderr = bundle?.getString("stderr").orEmpty(),
		)
	}
}

/** Receives Termux's answers to RUN_COMMAND, which it sends through the PendingIntent Minori gave it. */
class TermuxResultReceiver : BroadcastReceiver() {
	override fun onReceive(context: Context, intent: Intent) {
		val extras = intent.extras ?: return

		// Termux puts the result in a Bundle extra; its key has changed between versions, so take the Bundle.
		@Suppress("DEPRECATION")
		val bundle = extras.keySet().firstNotNullOfOrNull { extras.get(it) as? Bundle }
		TermuxHypervisor.deliver(intent.getIntExtra(EXTRA_ID, -1), TermuxResult.from(bundle))
	}

	companion object {
		const val EXTRA_ID = "minori.termux.request"
	}
}
