package com.wonderhoylabs.minori.service

import android.content.Context
import android.content.Intent
import com.jcraft.jsch.JSchException
import com.wonderhoylabs.airi.client.Airi
import com.wonderhoylabs.minori.MinoriApp
import com.wonderhoylabs.minori.backend.RunningGuest
import com.wonderhoylabs.minori.backend.TermuxHypervisor
import com.wonderhoylabs.minori.model.BackendKind
import com.wonderhoylabs.minori.model.VmConfig
import com.wonderhoylabs.minori.model.VmState
import com.wonderhoylabs.minori.store.VmStore
import com.wonderhoylabs.minori.terminal.Ssh
import java.io.File
import java.io.IOException
import java.util.zip.ZipFile

/**
 * Puts a Minori machine's Linux apps on the Android launcher through Airi. Minori installs Airi's agent in the guest,
 * taken from the installed Airi app so the two always match, with Airi's own installer and Airi's token. Airi then
 * reaches the agent through Minori's [AiriProvider], which connects it to the guest's port 47821, so nothing is
 * published on the phone and Android's own terminal can run Airi's agent at the same time.
 */
object AiriSetup {
	/** The port Airi's agent listens on inside a VM. */
	const val PORT = 47821
	private const val LEGACY_LABEL = "Airi"
	private const val LOOP_ASSET = "minori-airi.sh"
	private const val SSH_TIMEOUT_MS = 5 * 60 * 1000L
	private const val LOGIN_RETRY_MS = 2 * 60 * 1000L
	private const val LOGIN_RETRY_POLL_MS = 2000L

	/** Installs Xwayland if it's missing, with the package manager the machine has. Its user can sudo without a password. */
	private const val XWAYLAND =
		"command -v Xwayland >/dev/null && exit 0; " +
			"if command -v apt-get >/dev/null; then " +
			"sudo -n env DEBIAN_FRONTEND=noninteractive apt-get install -y -q xwayland || " +
			"{ sudo -n apt-get update -q && sudo -n env DEBIAN_FRONTEND=noninteractive apt-get install -y -q xwayland; }; " +
			"elif command -v dnf >/dev/null; then sudo -n dnf install -y -q xorg-x11-server-Xwayland; " +
			"else echo \"no apt-get or dnf to install Xwayland with\"; exit 1; fi; " +
			// The agent looks for Xwayland when it starts.
			"command -v Xwayland >/dev/null && systemctl --user restart airi-agent"
	private const val POLL_MS = 1000L
	private val TOKEN = Regex("\\b[0-9a-fA-F]{64}\\b")

	fun installed(context: Context): Boolean = Airi.isInstalled(context)

	/** Whether the installed Airi reaches machines through Minori, which Airi 0.4.0 and later do. */
	fun knowsMachines(context: Context): Boolean = Airi.canOpenMinoriApps(context)

	/** Airi's own screen, where its Linux apps are. */
	fun openIntent(context: Context): Intent? = context.packageManager.getLaunchIntentForPackage(Airi.PACKAGE)

	/** The token in Airi's setup command, `sh …/install.sh <token>`, or a bare token, or null if there isn't one. */
	fun token(command: String): String? = TOKEN.find(command)?.value?.lowercase()

	/**
	 * Whether Airi's agent could run in [vm]. A VM needs Minori's guest agent to reach it; a Termux userland is on the
	 * phone's own loopback, but needs a distribution with glibc.
	 */
	fun canSetUp(vm: VmConfig): Boolean = when (vm.backend) {
		BackendKind.TERMUX -> TermuxHypervisor.DISTROS.firstOrNull { it.id == vm.distro }?.airiLibraries != null
		else -> vm.agent
	}

	/** The port Airi's agent listens on in [vm]: its own one in a Termux machine, which shares the phone's loopback. */
	fun agentPort(vm: VmConfig): Int = if (vm.backend == BackendKind.TERMUX) TermuxHypervisor.airiPort(vm) else PORT

	/** Whether Airi's agent is installed in [vm]. */
	fun isSetUp(vm: VmConfig): Boolean = canSetUp(vm) && (vm.airi || legacyForward(vm))

	/**
	 * Minori 0.2 published the guest's port 47821 on the phone's, where Airi looked, so only one Linux could have it.
	 * Airi now comes through Minori instead, so that forward goes and the machine is marked as set up.
	 */
	private fun legacyForward(vm: VmConfig): Boolean = vm.network.forwards.any {
		it.guestPort == PORT && it.hostPort == PORT && it.label == LEGACY_LABEL
	}

	/** Moves machines set up by Minori 0.2 to the new way. Returns how many changed. */
	fun migrate(vms: VmStore): Int {
		var changed = 0
		for (vm in vms.vms.value) {
			if (!legacyForward(vm)) continue
			vms.update(vm.id) { markSetUp(it) }
			changed++
		}
		return changed
	}

	/** [vm] with Airi set up, and without the phone port Minori 0.2 published for it. */
	fun markSetUp(vm: VmConfig): VmConfig = vm.copy(
		airi = true,
		network = vm.network.copy(
			forwards = vm.network.forwards.filterNot {
				it.guestPort == PORT && it.hostPort == PORT && it.label == LEGACY_LABEL
			},
		),
	)

	/**
	 * Starts [vm] if it isn't running, and waits until its SSH server answers, for [install]. Blocks; call on a background
	 * thread.
	 */
	fun startForSsh(app: MinoriApp, vm: VmConfig, job: Job): RunningGuest {
		if (app.machines.guest(vm.id) == null) {
			job.step = "Starting ${vm.name}"
			app.machines.start(vm.id)
		}
		// Starting has its own time limit in each backend, and a Termux machine's first start, which installs its
		// distribution, can take many minutes. Only the wait for SSH once it runs is limited here.
		var deadline = Long.MAX_VALUE
		while (System.currentTimeMillis() < deadline) {
			when (val state = app.machines.state(vm.id)) {
				is VmState.Failed -> throw IOException(state.message)

				VmState.Stopped, VmState.Stopping -> throw IOException("${vm.name} stopped")

				VmState.Starting -> job.step = "Waiting for ${vm.name} to start"

				VmState.Running -> {
					val guest = app.machines.guest(vm.id)
					// A VM counts as running as soon as it boots, before its guest agent answers, and SSH goes through
					// that agent, so wait for it to list SSH's port. A Termux machine has no agent, and runs once SSH does.
					val ports = guest?.let { runCatching { it.listeningPorts() }.getOrElse { emptyList() } }
					val ready = if (vm.backend == BackendKind.TERMUX) guest != null else ports?.contains(vm.access.sshPort) == true
					if (ready) return guest!!
					if (deadline == Long.MAX_VALUE) deadline = System.currentTimeMillis() + SSH_TIMEOUT_MS
					job.step = "Waiting for ${vm.name}'s SSH server"
				}
			}
			Thread.sleep(POLL_MS)
		}
		throw IOException("${vm.name}'s SSH server didn't answer")
	}

	/** The agent and installer from the installed Airi app's own package. */
	private fun agentFiles(context: Context): Pair<ByteArray, ByteArray> {
		val apk = context.packageManager.getApplicationInfo(Airi.PACKAGE, 0).sourceDir
		ZipFile(apk).use { zip ->
			fun read(name: String): ByteArray =
				zip.getEntry(name)?.let { entry -> zip.getInputStream(entry).use { it.readBytes() } }
					?: throw IOException("This version of Airi doesn't carry its agent. Update Airi.")
			return read("assets/airi-agent") to read("assets/install.sh")
		}
	}

	/**
	 * Installs Airi's agent in the running [vm] over SSH, and marks it as set up. Returns what the installer printed.
	 * Blocks; call on a background thread.
	 */
	fun install(context: Context, vms: VmStore, vm: VmConfig, guest: RunningGuest, token: String, job: Job): String {
		if (!knowsMachines(context)) throw IOException("This Airi only knows Android's terminal. Update Airi to 0.4.0.")
		job.step = "Taking the agent from Airi"
		val (agent, installer) = agentFiles(context)
		val dir = vms.dir(vm.id)
		val termux = vm.backend == BackendKind.TERMUX
		val key = File(dir, "ssh/id_rsa").takeIf { it.exists() && !termux }?.readText()

		fun runOnce(command: String, stdin: ByteArray?): Ssh.Result = Ssh.run(
			// The same name and user as Minori's own console for each kind of machine, so the host key it saw matches.
			hostAlias = if (termux) "termux-${vm.id}" else "vm-${vm.id}.minori",
			user = if (termux) "root" else vm.access.user,
			password = vm.access.password,
			privateKey = key,
			knownHosts = File(dir, "ssh/known_hosts"),
			open = { guest.connect(if (termux) TermuxHypervisor(context).port(vm) else vm.access.sshPort) },
			// A key seen for the first time is trusted, as Minori's own SSH screen would on a yes; a changed one isn't.
			confirmHostKey = { message -> "can't be established" in message },
			command = command,
			stdin = stdin,
		)

		// Just after boot the guest's agent can answer once and then drop out for a moment while the machine settles,
		// so a login that fails before any command runs is tried again for a while, not shown as a stack of errors.
		fun run(command: String, stdin: ByteArray? = null): Ssh.Result {
			val deadline = System.currentTimeMillis() + LOGIN_RETRY_MS
			while (true) {
				try {
					return runOnce(command, stdin)
				} catch (error: JSchException) {
					val beforeLogin = error.message.orEmpty().startsWith("Session.connect")
					if (!beforeLogin || System.currentTimeMillis() > deadline) {
						throw IOException("Minori couldn't log in to ${vm.name} over SSH. Try again in a minute.", error)
					}
					job.step = "Waiting for ${vm.name}'s SSH server"
					Thread.sleep(LOGIN_RETRY_POLL_MS)
				}
			}
		}
		val output =
			if (termux) {
				installInTermux(context, vm, agent, token, job, ::run)
			} else {
				// Airi's installer deletes the folder it runs from when it's done.
				val folder = "\$HOME/.cache/minori-airi"
				job.step = "Copying Airi's agent into ${vm.name}"
				check(run("mkdir -p $folder && cat > $folder/airi-agent", agent), "copy Airi's agent")
				check(run("cat > $folder/install.sh", installer), "copy Airi's installer")
				job.step = "Running Airi's installer"
				val installed = run("sh $folder/install.sh $token").also { check(it, "install Airi's agent") }.output
				// Android's terminal comes with Xwayland, which Airi needs for X11 apps; a cloud image doesn't. Without
				// it Airi still works for Wayland apps, so a failure here is only noted.
				job.step = "Adding Xwayland for X11 apps"
				val xwayland = run(XWAYLAND)
				if (xwayland.exitCode == 0) installed else "$installed\n\nXwayland: ${xwayland.output.trim().lines().lastOrNull()}"
			}
		vms.update(vm.id) { markSetUp(it) }
		return output.trim()
	}

	/**
	 * A Termux machine has no systemd for Airi's installer to use, and shares the phone's loopback, so Minori installs
	 * the agent itself, with the libraries it needs, and runs it on the machine's own port with the minori-airi loop,
	 * now and each time the machine starts.
	 */
	private fun installInTermux(
		context: Context,
		vm: VmConfig,
		agent: ByteArray,
		token: String,
		job: Job,
		run: (String, ByteArray?) -> Ssh.Result,
	): String {
		val distro = TermuxHypervisor.DISTROS.firstOrNull { it.id == vm.distro }
		val libraries =
			distro?.airiLibraries ?: throw IOException("Airi's agent doesn't run on ${distro?.name ?: "this"} Linux.")
		job.step = "Installing what Airi needs in ${vm.name}"
		val packages = run(libraries, null)
		check(packages, "install the libraries Airi needs")
		job.step = "Copying Airi's agent into ${vm.name}"
		check(
			run(
				"mkdir -p \$HOME/.local/bin && cat > \$HOME/.local/bin/airi-agent && chmod 755 \$HOME/.local/bin/airi-agent",
				agent,
			),
			"copy Airi's agent",
		)
		val loop = context.assets.open(LOOP_ASSET).use { it.readBytes() }
		check(
			run("cat > ${TermuxHypervisor.AIRI_LOOP} && chmod 755 ${TermuxHypervisor.AIRI_LOOP}", loop),
			"copy Minori's loop for Airi",
		)
		check(
			run("mkdir -p \$HOME/.config/airi && (umask 077 && printf '%s\\n' $token > \$HOME/.config/airi/token)", null),
			"save Airi's token",
		)
		job.step = "Starting Airi's agent"
		val port = TermuxHypervisor.airiPort(vm)
		val ssh = TermuxHypervisor(context).port(vm)
		check(run("nohup ${TermuxHypervisor.AIRI_LOOP} $port $ssh >/dev/null 2>&1 &", null), "start Airi's agent")
		return packages.output.lines().takeLast(2).joinToString("\n")
	}

	private fun check(result: Ssh.Result, what: String) {
		if (result.exitCode != 0) {
			val output = result.output.trim().lines().takeLast(4).joinToString("\n")
			throw IOException("Couldn't $what (exit code ${result.exitCode}).${if (output.isEmpty()) "" else "\n$output"}")
		}
	}
}
