package com.wonderhoylabs.minori.service

import android.content.Context
import android.content.Intent
import com.wonderhoylabs.airi.client.Airi
import com.wonderhoylabs.minori.MinoriApp
import com.wonderhoylabs.minori.backend.RunningGuest
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
	/** The port Airi's agent listens on inside the guest. */
	const val PORT = 47821
	private const val LEGACY_LABEL = "Airi"
	private const val START_TIMEOUT_MS = 5 * 60 * 1000L
	private const val POLL_MS = 1000L
	private val TOKEN = Regex("\\b[0-9a-fA-F]{64}\\b")

	fun installed(context: Context): Boolean = Airi.isInstalled(context)

	/** Whether the installed Airi reaches machines through Minori, which Airi 0.4.0 and later do. */
	fun knowsMachines(context: Context): Boolean = Airi.canOpenMinoriApps(context)

	/** Airi's own screen, where its Linux apps are. */
	fun openIntent(context: Context): Intent? = context.packageManager.getLaunchIntentForPackage(Airi.PACKAGE)

	/** The token in Airi's setup command, `sh …/install.sh <token>`, or a bare token, or null if there isn't one. */
	fun token(command: String): String? = TOKEN.find(command)?.value?.lowercase()

	/** Whether Airi's agent could run in [vm]: it needs Minori's guest agent, which a Termux userland doesn't have. */
	fun canSetUp(vm: VmConfig): Boolean = vm.backend != BackendKind.TERMUX && vm.agent

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
		val deadline = System.currentTimeMillis() + START_TIMEOUT_MS
		while (System.currentTimeMillis() < deadline) {
			val state = app.machines.state(vm.id)
			if (state is VmState.Failed) throw IOException(state.message)
			val guest = app.machines.guest(vm.id)
			if (guest != null && guest.listeningPorts()?.contains(vm.access.sshPort) != false) return guest
			job.step = "Waiting for ${vm.name} to start"
			Thread.sleep(POLL_MS)
		}
		throw IOException("${vm.name} didn't start in time")
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
		val key = File(dir, "ssh/id_rsa").takeIf { it.exists() }?.readText()

		fun run(command: String, stdin: ByteArray? = null): Ssh.Result = Ssh.run(
			hostAlias = "vm-${vm.id}.minori",
			user = vm.access.user,
			password = vm.access.password,
			privateKey = key,
			knownHosts = File(dir, "ssh/known_hosts"),
			open = { guest.connect(vm.access.sshPort) },
			// A key seen for the first time is trusted, as Minori's own SSH screen would on a yes; a changed one isn't.
			confirmHostKey = { message -> "can't be established" in message },
			command = command,
			stdin = stdin,
		)
		// Airi's installer deletes the folder it runs from when it's done.
		val folder = "\$HOME/.cache/minori-airi"
		job.step = "Copying Airi's agent into ${vm.name}"
		check(run("mkdir -p $folder && cat > $folder/airi-agent", agent), "copy Airi's agent")
		check(run("cat > $folder/install.sh", installer), "copy Airi's installer")
		job.step = "Running Airi's installer"
		val result = run("sh $folder/install.sh $token")
		check(result, "install Airi's agent")
		vms.update(vm.id) { markSetUp(it) }
		return result.output.trim()
	}

	private fun check(result: Ssh.Result, what: String) {
		if (result.exitCode != 0) {
			val output = result.output.trim().lines().takeLast(4).joinToString("\n")
			throw IOException("Couldn't $what (exit code ${result.exitCode}).${if (output.isEmpty()) "" else "\n$output"}")
		}
	}
}
