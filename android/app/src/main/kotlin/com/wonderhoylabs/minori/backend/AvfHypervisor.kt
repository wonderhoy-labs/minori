package com.wonderhoylabs.minori.backend

import android.content.Context
import android.os.Build
import android.os.ParcelFileDescriptor
import android.system.virtualmachine.VirtualMachine
import android.system.virtualmachine.VirtualMachineCallback
import android.system.virtualmachine.VirtualMachineConfig
import android.system.virtualmachine.VirtualMachineCustomImageConfig
import android.system.virtualmachine.VirtualMachineException
import android.system.virtualmachine.VirtualMachineManager
import com.wonderhoylabs.minori.model.BackendKind
import com.wonderhoylabs.minori.model.CpuMode
import com.wonderhoylabs.minori.model.DiskRole
import com.wonderhoylabs.minori.model.VmConfig
import com.wonderhoylabs.minori.setup.HiddenApi
import com.wonderhoylabs.minori.setup.Privileges
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.Executors

/**
 * Runs machines with Android's Virtualization Framework: crosvm, started by the system's virtualization service,
 * booting the guest's own Linux kernel with its disks attached as virtio block devices.
 *
 * `VirtualMachineManager`, `VirtualMachineConfig` and `VirtualMachine` are system API. Custom kernels and disks
 * (`VirtualMachineCustomImageConfig`) and console input are hidden API on Android 17, reached through
 * [HiddenApi]. Everything AVF-specific is in this one file, so it's the only thing to change when Android does.
 */
class AvfHypervisor(private val context: Context) : Hypervisor {
	override val kind = BackendKind.AVF

	private val executor = Executors.newSingleThreadExecutor { Thread(it, "avf-callbacks").apply { isDaemon = true } }

	override fun availability(): Availability {
		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
			return Availability.Unavailable("Android's Virtualization Framework needs Android 14 or newer.")
		}
		if (!context.packageManager.hasSystemFeature(Privileges.AVF_FEATURE)) {
			return Availability.Unavailable("This device doesn't have Android's Virtualization Framework.")
		}
		val missing = Privileges.missingPermissions(context)
		if (missing.isNotEmpty()) {
			return Availability.Unavailable("Minori hasn't been given permission to run virtual machines yet.")
		}
		if (!HiddenApi.exempted) {
			return Availability.Unavailable("This Android version blocks the AVF calls Minori needs to boot your own kernel.")
		}
		val capabilities = runCatching {
			manager().capabilities
		}.getOrElse { return Availability.Unavailable(it.message ?: "AVF didn't answer") }
		if (capabilities and VirtualMachineManager.CAPABILITY_NON_PROTECTED_VM == 0) {
			return Availability.Unavailable("AVF on this device only runs protected VMs, which can't boot your own images.")
		}
		return Availability.Ready
	}

	private fun manager(): VirtualMachineManager = context.getSystemService(VirtualMachineManager::class.java)
		?: throw IOException("AVF's VirtualMachineManager isn't available on this device")

	override fun start(vm: VmConfig, dir: File, events: GuestEvents): RunningGuest {
		val boot = vm.boot ?: throw IOException("${vm.name} has no kernel to boot. Pick a bootable image for it.")
		val kernel = File(dir, boot.kernelFile)
		if (!kernel.exists()) throw IOException("${vm.name}'s kernel is missing. Edit the VM and choose its boot disk again.")
		// AVF only takes letters, digits, - and _ in a name, so it gets the instance name, not the user's.
		val name = "minori-${vm.id}"
		val image =
			VirtualMachineCustomImageConfig
				.Builder()
				.setName(name)
				.setKernelPath(kernel.path)
				.apply { boot.initrdFile?.let { File(dir, it) }?.takeIf { it.exists() }?.let { setInitrdPath(it.path) } }
				.apply { boot.cmdline.split(' ').filter { it.isNotBlank() }.forEach { addParam(it) } }
				// The VM has no network card (its internet comes through the agent, which starts later), so don't
				// let systemd hold boot for two minutes waiting for one.
				.apply { if ("wait-online" !in boot.cmdline) addParam(SKIP_WAIT_ONLINE) }
				.apply {
					// The boot disk first, so it's /dev/vda, then the rest in the order the user set.
					val ordered = vm.disks.sortedBy {
						if (it.id == boot.diskId) {
							0
						} else if (it.role == DiskRole.SEED) {
							2
						} else {
							1
						}
					}
					for (disk in ordered) {
						val path = File(dir, disk.file).path
						addDisk(
							if (disk.readOnly || disk.role == DiskRole.CDROM || disk.role == DiskRole.SEED) {
								VirtualMachineCustomImageConfig.Disk.RODisk(path)
							} else {
								VirtualMachineCustomImageConfig.Disk.RWDisk(path)
							},
						)
					}
				} // The guest's internet comes from Minori's own NAT through the agent (net/GuestNetwork.kt), which works
				// whether or not this Android build lets apps use AVF's network.
				.useNetwork(false)
				.build()
		val config =
			VirtualMachineConfig
				.Builder(context)
				.setCustomImageConfig(image)
				.setProtectedVm(false)
				.setMemoryBytes(vm.memoryMib.toLong() * 1024 * 1024)
				.setCpuTopology(
					if (vm.cpu ==
						CpuMode.ONE
					) {
						VirtualMachineConfig.CPU_TOPOLOGY_ONE_CPU
					} else {
						VirtualMachineConfig.CPU_TOPOLOGY_MATCH_HOST
					},
				)
				// AVF only gives the console of a debuggable VM to the app that started it.
				.setDebugLevel(VirtualMachineConfig.DEBUG_LEVEL_FULL)
				.setVmOutputCaptured(true)
				.setVmConsoleInputSupported(true)
				.setConsoleInputDevice(boot.consoleDevice)
				.build()
		val manager = manager()
		// The instance holds nothing for an unprotected custom VM, so it's made fresh each start with the current settings.
		runCatching { manager.delete(name) }
		val machine = manager.create(name, config)
		machine.setCallback(
			executor,
			object : VirtualMachineCallback {
				override fun onPayloadStarted(vm: VirtualMachine) {}

				override fun onPayloadReady(vm: VirtualMachine) {}

				override fun onPayloadFinished(vm: VirtualMachine, exitCode: Int) {}

				override fun onError(vm: VirtualMachine, errorCode: Int, message: String) = events.onError(message)

				override fun onStopped(vm: VirtualMachine, reason: Int) = events.onStopped(stopReason(reason))
			},
		)
		machine.run()
		val consoleOut = machine.consoleOutput
		val consoleIn = runCatching { machine.consoleInput }.getOrNull()
		val logOut = runCatching { machine.logOutput }.getOrNull()
		return AvfGuest(machine, consoleOut, consoleIn, logOut, vm.agent)
	}

	private class AvfGuest(
		private val machine: VirtualMachine,
		consoleOut: InputStream,
		consoleIn: OutputStream?,
		override val log: InputStream?,
		private val hasAgent: Boolean,
	) : RunningGuest,
		com.wonderhoylabs.minori.net.AgentGuest {
		override val console =
			object : ConsoleChannel {
				override val output = consoleOut
				override val input = consoleIn

				override fun close() {
					runCatching { consoleOut.close() }
					runCatching { consoleIn?.close() }
				}
			}

		private fun vsock(port: Long): GuestConnection {
			// AVF says "no such device" with a VirtualMachineException until something in the guest listens, such as
			// the agent a few seconds into boot. Callers expect an IOException for that, and retry.
			val socket =
				try {
					machine.connectVsock(port)
				} catch (error: VirtualMachineException) {
					throw IOException("Nothing in the guest is listening on vsock port $port yet", error)
				}
			// FileInputStream.available() asks the kernel with FIONREAD, which a vsock answers with EOPNOTSUPP, and
			// BufferedInputStream calls it after every short read. Saying "nothing buffered" is always true enough.
			val reader =
				object : java.io.FilterInputStream(ParcelFileDescriptor.AutoCloseInputStream(socket.dup())) {
					override fun available() = 0
				}
			val writer = ParcelFileDescriptor.AutoCloseOutputStream(socket)
			return object : GuestConnection {
				override val input: InputStream = reader
				override val output: OutputStream = writer

				override fun close() {
					runCatching { reader.close() }
					runCatching { writer.close() }
				}
			}
		}

		override fun connect(guestPort: Int): GuestConnection {
			if (!hasAgent) throw IOException("This VM doesn't have Minori's guest agent, which published ports need.")
			return Agent.connect(vsock(Agent.VSOCK_PORT), guestPort)
		}

		override fun agentRequest(request: String): GuestConnection {
			if (!hasAgent) throw IOException("This VM doesn't have Minori's guest agent, which published ports need.")
			val connection = vsock(Agent.VSOCK_PORT)
			try {
				Agent.request(connection, request)
			} catch (error: IOException) {
				connection.close()
				throw error
			}
			return connection
		}

		override fun listeningPorts(): List<Int>? = if (hasAgent) {
			runCatching {
				Agent.ports(vsock(Agent.VSOCK_PORT))
			}.getOrNull()
		} else {
			null
		}

		override fun requestShutdown(): Boolean =
			hasAgent && runCatching { Agent.poweroff(vsock(Agent.VSOCK_PORT)) }.getOrDefault(false)

		override fun forceStop() {
			runCatching { machine.stop() }
			runCatching { machine.close() }
		}
	}

	private fun stopReason(reason: Int): String? = when (reason) {
		VirtualMachineCallback.STOP_REASON_SHUTDOWN, VirtualMachineCallback.STOP_REASON_KILLED -> null
		VirtualMachineCallback.STOP_REASON_REBOOT -> "The guest rebooted. Start it again to boot it."
		VirtualMachineCallback.STOP_REASON_CRASH -> "The guest crashed."
		VirtualMachineCallback.STOP_REASON_START_FAILED -> "AVF couldn't start the VM. Check its kernel and disks."
		VirtualMachineCallback.STOP_REASON_VIRTUALIZATION_SERVICE_DIED -> "Android's virtualization service stopped."
		VirtualMachineCallback.STOP_REASON_INFRASTRUCTURE_ERROR -> "AVF hit an internal error."
		VirtualMachineCallback.STOP_REASON_HANGUP -> "The guest stopped responding."
		else -> "The VM stopped (AVF reason $reason)."
	}
}

/** Systemd's wait for a network card, which a Minori VM never has. */
private const val SKIP_WAIT_ONLINE = "systemd.mask=systemd-networkd-wait-online.service"
