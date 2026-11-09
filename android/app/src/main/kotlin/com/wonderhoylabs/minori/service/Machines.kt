package com.wonderhoylabs.minori.service

import android.content.Context
import android.util.Log
import com.wonderhoylabs.minori.backend.Availability
import com.wonderhoylabs.minori.backend.GuestEvents
import com.wonderhoylabs.minori.backend.Hypervisor
import com.wonderhoylabs.minori.backend.RunningGuest
import com.wonderhoylabs.minori.model.BackendKind
import com.wonderhoylabs.minori.model.VmState
import com.wonderhoylabs.minori.net.PortForwarder
import com.wonderhoylabs.minori.store.VmStore
import com.wonderhoylabs.minori.terminal.TerminalSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

/**
 * Starts and stops machines and keeps what belongs to a running one: its backend handle, its console's
 * terminal, its log and its published ports. The foreground [MinoriService] runs while any machine does, so Android
 * doesn't stop the process under them.
 */
class Machines(
	private val context: Context,
	private val store: VmStore,
	val forwarder: PortForwarder,
	private val backends: Map<BackendKind, Hypervisor>,
) {
	private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
	private val stateFlow = MutableStateFlow<Map<String, VmState>>(emptyMap())
	val states: StateFlow<Map<String, VmState>> = stateFlow.asStateFlow()

	private val guests = ConcurrentHashMap<String, RunningGuest>()
	private val consoles = ConcurrentHashMap<String, TerminalSession>()
	private val logs = ConcurrentHashMap<String, MutableStateFlow<String>>()

	fun state(id: String): VmState = stateFlow.value[id] ?: VmState.Stopped

	fun backend(kind: BackendKind): Hypervisor? = backends[kind]

	fun availability(kind: BackendKind): Availability =
		backends[kind]?.availability() ?: Availability.Unavailable("This backend isn't part of this build.")

	fun guest(id: String): RunningGuest? = guests[id]

	fun console(id: String): TerminalSession? = consoles[id]

	fun log(id: String): StateFlow<String> = logs.getOrPut(id) { MutableStateFlow("") }

	private fun set(id: String, state: VmState) = stateFlow.update { it + (id to state) }

	private fun appendLog(id: String, text: String) {
		val flow = logs.getOrPut(id) { MutableStateFlow("") }
		flow.update { (it + text).takeLast(MAX_LOG) }
	}

	fun start(id: String) {
		val vm = store.get(id) ?: return
		val current = state(id)
		if (current == VmState.Running || current == VmState.Starting) return
		val backend = backends[vm.backend]
		if (backend == null) {
			set(id, VmState.Failed("This build of Minori can't run ${vm.backend.name.lowercase()} machines."))
			return
		}
		set(id, VmState.Starting)
		MinoriService.update(context)
		scope.launch {
			val availability = backend.availability()
			if (availability is Availability.Unavailable) {
				set(id, VmState.Failed(availability.reason))
				MinoriService.update(context)
				return@launch
			}
			val events =
				object : GuestEvents {
					override fun onStopped(reason: String?) = stopped(id, reason)

					override fun onError(message: String) {
						appendLog(id, "\n[Minori] $message\n")
						stopped(id, message)
					}
				}
			try {
				val guest = backend.start(vm, store.dir(id), events)
				guests[id] = guest
				consoles[id] =
					TerminalSession(
						title = vm.name,
						output = guest.console.output,
						input = guest.console.input,
						onResize = { rows, cols -> guest.console.resize(rows, cols) },
					)
				guest.log?.let { stream ->
					thread(name = "log-$id", isDaemon = true) {
						val buffer = ByteArray(8192)
						runCatching {
							while (true) {
								val n = stream.read(buffer)
								if (n < 0) break
								appendLog(id, String(buffer, 0, n))
							}
						}
					}
				}
				set(id, VmState.Running)
				forwarder.startAll(id, vm.network.forwards, guest)
			} catch (error: Throwable) {
				Log.w(TAG, "Couldn't start ${vm.name}", error)
				set(id, VmState.Failed(error.message ?: error.javaClass.simpleName))
			}
			MinoriService.update(context)
		}
	}

	private fun stopped(id: String, reason: String?) {
		forwarder.stopAll(id)
		guests.remove(id)
		consoles.remove(id)?.close()
		set(id, if (reason == null) VmState.Stopped else VmState.Failed(reason))
		MinoriService.update(context)
	}

	/** Asks the guest to shut down, and pulls the plug if it can't or hasn't after a while. */
	fun stop(id: String) {
		val guest = guests[id] ?: return set(id, VmState.Stopped)
		set(id, VmState.Stopping)
		scope.launch {
			if (guest.requestShutdown()) {
				repeat(SHUTDOWN_WAIT_SECONDS) {
					if (!guests.containsKey(id)) return@launch
					delay(1000)
				}
			}
			forceStop(id)
		}
	}

	fun forceStop(id: String) {
		val guest = guests[id]
		if (guest == null) {
			set(id, VmState.Stopped)
			MinoriService.update(context)
			return
		}
		scope.launch {
			runCatching { guest.forceStop() }
			stopped(id, null)
		}
	}

	fun runningCount(): Int = stateFlow.value.values.count {
		it == VmState.Running || it == VmState.Starting ||
			it == VmState.Stopping
	}

	/** After the user edits a running VM's ports, publishes the new set. */
	fun refreshForwards(id: String) {
		val vm = store.get(id) ?: return
		val guest = guests[id] ?: return
		forwarder.replace(id, vm.network.forwards, guest)
	}

	fun stopAll() {
		guests.keys.toList().forEach { forceStop(it) }
	}

	companion object {
		private const val TAG = "MinoriMachines"
		private const val MAX_LOG = 64 * 1024
		private const val SHUTDOWN_WAIT_SECONDS = 30
	}
}
