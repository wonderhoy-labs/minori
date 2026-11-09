package com.wonderhoylabs.minori.backend

import com.wonderhoylabs.minori.model.BackendKind
import com.wonderhoylabs.minori.model.VmConfig
import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/** A byte stream to a service in the guest, such as its SSH server. */
interface GuestConnection : Closeable {
	val input: InputStream
	val output: OutputStream
}

/** A guest's console: what it prints on its serial port, and a way to type into it. */
interface ConsoleChannel : Closeable {
	val output: InputStream

	/** Null if this backend can only show the console, not type into it. */
	val input: OutputStream?

	fun resize(rows: Int, cols: Int) {}
}

/** Whether a backend can run machines on this phone, and if not, why, in words for the user. */
sealed interface Availability {
	data object Ready : Availability

	data class Unavailable(val reason: String) : Availability
}

/** What a backend tells Minori about a machine it's running. */
interface GuestEvents {
	fun onStopped(reason: String?)

	fun onError(message: String)
}

/** A machine that's running. */
interface RunningGuest {
	val console: ConsoleChannel

	/** The kernel's log (AVF's virtio console), if the backend has one apart from the console. */
	val log: InputStream?

	/** Opens a TCP connection to [guestPort] inside the guest. Throws if nothing listens there. */
	fun connect(guestPort: Int): GuestConnection

	/** Asks the guest's agent which TCP ports something listens on, or null without an agent. */
	fun listeningPorts(): List<Int>?

	/** Asks the guest to shut down cleanly. Returns false if it can't, and only [forceStop] will do. */
	fun requestShutdown(): Boolean

	/** Stops the machine at once, like pulling the plug. */
	fun forceStop()
}

/** An engine that runs machines: AVF, the demo shell, or Termux. */
interface Hypervisor {
	val kind: BackendKind

	fun availability(): Availability

	/** Starts [vm], whose files are in [dir]. Blocks until it's running or has failed. */
	fun start(vm: VmConfig, dir: File, events: GuestEvents): RunningGuest
}
