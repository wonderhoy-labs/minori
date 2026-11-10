package com.wonderhoylabs.minori.service

import android.content.Context
import android.content.Intent
import com.wonderhoylabs.airi.client.Airi
import com.wonderhoylabs.minori.backend.RunningGuest
import com.wonderhoylabs.minori.model.Exposure
import com.wonderhoylabs.minori.model.PortForward
import com.wonderhoylabs.minori.model.VmConfig
import com.wonderhoylabs.minori.store.VmStore
import com.wonderhoylabs.minori.terminal.Ssh
import java.io.File
import java.io.IOException
import java.util.zip.ZipFile

/**
 * Puts a Minori machine's Linux apps on the Android launcher through Airi. Airi normally shows apps from Android's own
 * Linux terminal: its agent runs there and Airi reaches it on the phone's port 47821. Minori installs the same agent,
 * taken from the installed Airi app so the two always match, with Airi's own installer and the token from Airi's setup
 * command, then publishes the guest's port 47821 on the phone's loopback, where Airi already looks. Nothing in Airi
 * changes. Only one of Android's terminal and a Minori machine can hold that port at a time.
 */
object AiriSetup {
	/** The port Airi's agent listens on, and the one Airi connects to on the phone. */
	const val PORT = 47821
	private const val LABEL = "Airi"
	private val TOKEN = Regex("\\b[0-9a-fA-F]{64}\\b")

	fun installed(context: Context): Boolean = Airi.isInstalled(context)

	/** Airi's own screen, where its setup command and its Linux apps are. */
	fun openIntent(context: Context): Intent? = context.packageManager.getLaunchIntentForPackage(Airi.PACKAGE)

	/** The token in Airi's setup command, `sh …/install.sh <token>`, or null if there isn't one. */
	fun token(command: String): String? = TOKEN.find(command)?.value?.lowercase()

	/** Whether [vm] already gives Airi its agent's port. */
	fun isSetUp(vm: VmConfig): Boolean = vm.network.forwards.any { it.guestPort == PORT && it.hostPort == PORT }

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
	 * Installs Airi's agent in the running [vm] over SSH and publishes its port. Returns what the installer printed.
	 * Blocks; call on a background thread.
	 */
	fun install(context: Context, vms: VmStore, vm: VmConfig, guest: RunningGuest, token: String, job: Job): String {
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
		vms.update(vm.id) { current ->
			val others = current.network.forwards.filterNot { it.guestPort == PORT || it.hostPort == PORT }
			val forward = PortForward(hostPort = PORT, guestPort = PORT, label = LABEL, exposure = Exposure.LOOPBACK)
			current.copy(network = current.network.copy(forwards = others + forward))
		}
		return result.output.trim()
	}

	private fun check(result: Ssh.Result, what: String) {
		if (result.exitCode != 0) {
			val output = result.output.trim().lines().takeLast(4).joinToString("\n")
			throw IOException("Couldn't $what (exit code ${result.exitCode}).${if (output.isEmpty()) "" else "\n$output"}")
		}
	}
}
