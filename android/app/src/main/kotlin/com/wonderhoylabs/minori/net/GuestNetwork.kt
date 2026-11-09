package com.wonderhoylabs.minori.net

import android.util.Log
import com.wonderhoylabs.minori.core.Native
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import kotlin.concurrent.thread

/** How a guest's internet is doing, for its page. */
enum class NetworkState {
	/** Waiting for the guest's agent, which starts a little after the guest boots. */
	Waiting,
	Connected,
	Off,
}

/**
 * Gives a guest internet through its agent: `minori-agent` puts the guest's packets on a TUN device and sends them
 * here, and saki-core's NAT turns them into the app's own sockets. It keeps trying while the VM runs, so it
 * connects once the agent is up and reconnects if the agent restarts.
 */
class GuestNetwork(private val name: String, private val guest: AgentGuest) {
	private val stateFlow = MutableStateFlow(NetworkState.Waiting)
	val state: StateFlow<NetworkState> = stateFlow.asStateFlow()

	@Volatile private var running = true

	@Volatile private var connection: com.wonderhoylabs.minori.backend.GuestConnection? = null

	private val loop =
		thread(name = "net-$name", isDaemon = true) {
			while (running) {
				try {
					serveOnce()
				} catch (error: IOException) {
					Log.i(TAG, "$name's network: ${error.message}")
				}
				stateFlow.value = NetworkState.Waiting
				if (running) runCatching { Thread.sleep(RETRY_MS) }
			}
			stateFlow.value = NetworkState.Off
		}

	private fun serveOnce() {
		val link = guest.agentRequest("NET")
		connection = link
		val nat = Native.natStart()
		stateFlow.value = NetworkState.Connected
		val down =
			thread(name = "net-$name-down", isDaemon = true) {
				val out = DataOutputStream(link.output.buffered(BUFFER))
				val buffer = ByteArray(MAX_PACKET)
				try {
					while (true) {
						val length = Native.natOutput(nat, buffer)
						if (length < 0) break
						out.writeShort(length)
						out.write(buffer, 0, length)
						out.flush()
					}
				} catch (_: IOException) {
				}
				runCatching { link.close() }
			}
		try {
			val input = DataInputStream(link.input.buffered(BUFFER))
			val buffer = ByteArray(MAX_PACKET)
			while (running) {
				val length = input.readUnsignedShort()
				input.readFully(buffer, 0, length)
				Native.natInput(nat, buffer, length)
			}
		} catch (_: IOException) {
		} finally {
			Native.natStop(nat)
			runCatching { link.close() }
			// natStop makes natOutput return, so this ends; only then is the NAT freed.
			down.join()
			Native.natFree(nat)
			connection = null
		}
	}

	fun stop() {
		running = false
		runCatching { connection?.close() }
		loop.interrupt()
	}

	companion object {
		private const val TAG = "MinoriNet"
		private const val RETRY_MS = 3000L
		private const val BUFFER = 64 * 1024
		private const val MAX_PACKET = 65_535
	}
}
