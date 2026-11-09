package com.wonderhoylabs.minori.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.UUID

/** Which engine runs a machine. Shown in the list, so a Termux session is never mistaken for a VM. */
@Serializable
enum class BackendKind {
	/** A real virtual machine through Android's Virtualization Framework. */
	@SerialName("avf")
	AVF,

	/** A shell on this phone that stands in for a VM, for trying Minori where AVF isn't available. Debug builds. */
	@SerialName("demo")
	DEMO,

	/** A Linux userland in Termux through proot. Not a VM: it shares the phone's kernel. */
	@SerialName("termux")
	TERMUX,
}

@Serializable
enum class CpuMode {
	/** One virtual CPU. */
	@SerialName("one")
	ONE,

	/** One virtual CPU per CPU core of the phone, which is what Android's own Terminal uses. */
	@SerialName("match_host")
	MATCH_HOST,
}

@Serializable
enum class DiskRole {
	/** The disk the guest boots from. */
	@SerialName("root")
	ROOT,

	/** Extra storage. */
	@SerialName("data")
	DATA,

	/** The cloud-init seed Minori writes for first boot. */
	@SerialName("seed")
	SEED,

	/** An ISO, attached read-only. */
	@SerialName("cdrom")
	CDROM,
}

@Serializable
data class VmDisk(
	val id: String = UUID.randomUUID().toString(),
	/** The file name in the VM's folder. */
	val file: String,
	val label: String,
	val role: DiskRole,
	val readOnly: Boolean = false,
	val format: String = "raw",
)

@Serializable
data class BootSource(val kind: String, val number: Int? = null)

/** What minori-core's inspect found to boot. Mirrors `minori_core::inspect::BootPlan`. */
@Serializable
data class BootPlan(
	val source: BootSource,
	val kernel: String,
	val initrd: String? = null,
	val cmdline: String,
	val description: String = "",
)

@Serializable
data class BootSettings(
	/** The disk the kernel came from. */
	val diskId: String,
	val plan: BootPlan,
	/** The kernel and initrd Minori extracted, in the VM's folder. */
	val kernelFile: String = "boot/kernel",
	val initrdFile: String? = "boot/initrd",
	val cmdline: String,
	/** Where AVF sends console input: the first UART. */
	val consoleDevice: String = "ttyS0",
)

@Serializable
enum class Protocol {
	@SerialName("tcp")
	TCP,

	@SerialName("udp")
	UDP,
}

@Serializable
enum class Exposure {
	/** Only this phone can connect. */
	@SerialName("loopback")
	LOOPBACK,

	/** Other devices on the same network can connect too, subject to the allow and deny lists. */
	@SerialName("network")
	NETWORK,
}

/** A guest port published on the phone. */
@Serializable
data class PortForward(
	val id: String = UUID.randomUUID().toString(),
	val label: String = "",
	val protocol: Protocol = Protocol.TCP,
	val hostPort: Int,
	val guestPort: Int,
	val exposure: Exposure = Exposure.LOOPBACK,
	/** Addresses or CIDR ranges allowed to connect. Empty allows everyone who can reach the port. */
	val allow: List<String> = emptyList(),
	/** Addresses or CIDR ranges always refused, even if allowed. */
	val deny: List<String> = emptyList(),
	val enabled: Boolean = true,
)

@Serializable
data class NetworkSettings(
	/** Gives the guest AVF's own network, which reaches the internet through the phone. */
	val internet: Boolean = false,
	val forwards: List<PortForward> = emptyList(),
)

/** How Minori's Connect buttons log in. */
@Serializable
data class AccessSettings(
	val user: String = "user",
	val password: String? = null,
	val sshPort: Int = 22,
	val vncPort: Int = 5900,
	val vncPassword: String? = null,
	val rdpPort: Int = 3389,
)

@Serializable
data class VmConfig(
	val id: String = UUID.randomUUID().toString(),
	val name: String,
	val createdAt: Long = System.currentTimeMillis(),
	val backend: BackendKind = BackendKind.AVF,
	val cpu: CpuMode = CpuMode.MATCH_HOST,
	val memoryMib: Int = 2048,
	val disks: List<VmDisk> = emptyList(),
	val boot: BootSettings? = null,
	val network: NetworkSettings = NetworkSettings(),
	val access: AccessSettings = AccessSettings(),
	/** The OS, as the image says it, such as "Debian GNU/Linux 13 (trixie)". */
	val os: String? = null,
	val notes: String = "",
	val autoStart: Boolean = false,
	/** The catalog template it was made from, if any. */
	val template: String? = null,
	/** Whether minori-agent is installed in the guest, which published ports need. */
	val agent: Boolean = false,
	/** For the Termux backend: the proot-distro name. */
	val distro: String? = null,
) {
	val rootDisk: VmDisk? get() = disks.firstOrNull { it.role == DiskRole.ROOT }
}

/** What a machine is doing now. Not stored: it's always worked out from the backend. */
sealed interface VmState {
	data object Stopped : VmState

	data object Starting : VmState

	data object Running : VmState

	data object Stopping : VmState

	data class Failed(val message: String) : VmState
}
