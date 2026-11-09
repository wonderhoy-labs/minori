package com.wonderhoylabs.rui.net

import android.util.Log
import com.wonderhoylabs.rui.backend.GuestConnection
import com.wonderhoylabs.rui.backend.RunningGuest
import com.wonderhoylabs.rui.model.Exposure
import com.wonderhoylabs.rui.model.PortForward
import com.wonderhoylabs.rui.model.Protocol
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketAddress
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

/** How one published port is doing, for the VM page. */
data class ForwardStatus(
	val vmId: String,
	val forward: PortForward,
	val listening: Boolean,
	val error: String? = null,
	val open: Int = 0,
	val served: Int = 0,
	val refused: Int = 0,
	/** Made by a Connect button for an app outside Rui, not by the user. Gone when the VM stops. */
	val temporary: Boolean = false,
)

/**
 * Publishes guest ports on the phone. Each port is a listening socket in Rui's own process, so no root and no
 * iptables are involved: a connection is accepted, checked against the [Firewall], and spliced to a connection
 * the backend opens into the guest. Nothing is published unless the user adds it.
 */
class PortForwarder {
	private val state = MutableStateFlow<Map<String, ForwardStatus>>(emptyMap())
	val status: StateFlow<Map<String, ForwardStatus>> = state.asStateFlow()

	private val listeners = ConcurrentHashMap<String, Closeable>()

	fun startAll(
		vmId: String,
		forwards: List<PortForward>,
		guest: RunningGuest,
	) {
		forwards.filter { it.enabled }.forEach { start(vmId, it, guest, temporary = false) }
	}

	fun stopAll(vmId: String) {
		state.value.values.filter { it.vmId == vmId }.forEach { stop(it.forward.id) }
	}

	fun stop(forwardId: String) {
		listeners.remove(forwardId)?.let { runCatching { it.close() } }
		state.update { it - forwardId }
	}

	/** Restarts one VM's forwards after the user edits them. */
	fun replace(
		vmId: String,
		forwards: List<PortForward>,
		guest: RunningGuest,
	) {
		state.value.values.filter { it.vmId == vmId && !it.temporary }.forEach { stop(it.forward.id) }
		startAll(vmId, forwards, guest)
	}

	/**
	 * A loopback-only port for an app outside Rui, such as an RDP client, reusing one already published for
	 * [guestPort] if there is one. Returns the port on the phone.
	 */
	fun temporary(
		vmId: String,
		guestPort: Int,
		guest: RunningGuest,
	): Int {
		state.value.values
			.firstOrNull { it.vmId == vmId && it.forward.guestPort == guestPort && it.forward.protocol == Protocol.TCP && it.listening }
			?.let { return it.forward.hostPort }
		val forward = PortForward(label = "For another app", hostPort = 0, guestPort = guestPort)
		return start(vmId, forward, guest, temporary = true)
	}

	private fun start(
		vmId: String,
		forward: PortForward,
		guest: RunningGuest,
		temporary: Boolean,
	): Int {
		// 127.0.0.1, not getLoopbackAddress(), which is ::1 on Android: the page and other apps say 127.0.0.1.
		val bind = if (forward.exposure == Exposure.LOOPBACK) InetAddress.getByName("127.0.0.1") else InetAddress.getByName("::")
		return try {
			when (forward.protocol) {
				Protocol.TCP -> startTcp(vmId, forward, guest, bind, temporary)
				Protocol.UDP -> startUdp(vmId, forward, guest, bind, temporary)
			}
		} catch (error: IOException) {
			state.update { it + (forward.id to ForwardStatus(vmId, forward, listening = false, error = describe(error, forward))) }
			-1
		}
	}

	private fun describe(
		error: IOException,
		forward: PortForward,
	): String =
		if (error.message?.contains("EADDRINUSE") == true || error is java.net.BindException) {
			"Port ${forward.hostPort} is already in use on this phone"
		} else if (forward.hostPort in 1..1023) {
			"Android doesn't let apps use ports below 1024"
		} else {
			error.message ?: "Couldn't open the port"
		}

	private fun startTcp(
		vmId: String,
		forward: PortForward,
		guest: RunningGuest,
		bind: InetAddress,
		temporary: Boolean,
	): Int {
		val server = ServerSocket()
		server.reuseAddress = true
		server.bind(InetSocketAddress(bind, forward.hostPort), BACKLOG)
		val published = forward.copy(hostPort = server.localPort)
		listeners[forward.id] = server
		state.update { it + (forward.id to ForwardStatus(vmId, published, listening = true, temporary = temporary)) }
		thread(name = "forward-${published.hostPort}", isDaemon = true) {
			while (!server.isClosed) {
				val client = runCatching { server.accept() }.getOrNull() ?: break
				val allowed = Firewall.allows(published, client.inetAddress)
				Log.i(TAG, "Port ${published.hostPort}: ${client.inetAddress.hostAddress} ${if (allowed) "allowed" else "refused"}")
				if (!allowed) {
					runCatching { client.close() }
					count(forward.id) { it.copy(refused = it.refused + 1) }
					continue
				}
				thread(name = "forward-${published.hostPort}-conn", isDaemon = true) { serve(forward.id, client, guest, published.guestPort) }
			}
		}
		return published.hostPort
	}

	private fun serve(
		forwardId: String,
		client: Socket,
		guest: RunningGuest,
		guestPort: Int,
	) {
		val remote =
			try {
				guest.connect(guestPort)
			} catch (error: IOException) {
				Log.i(TAG, "Guest port $guestPort refused: ${error.message}")
				runCatching { client.close() }
				return
			}
		count(forwardId) { it.copy(open = it.open + 1, served = it.served + 1) }
		client.tcpNoDelay = true
		splice(client.getInputStream(), client.getOutputStream(), remote) {
			runCatching { client.close() }
			count(forwardId) { it.copy(open = (it.open - 1).coerceAtLeast(0)) }
		}
	}

	private fun startUdp(
		vmId: String,
		forward: PortForward,
		guest: RunningGuest,
		bind: InetAddress,
		temporary: Boolean,
	): Int {
		val socket = DatagramSocket(InetSocketAddress(bind, forward.hostPort))
		val published = forward.copy(hostPort = socket.localPort)
		val sessions = ConcurrentHashMap<SocketAddress, DataOutputStream>()
		listeners[forward.id] =
			Closeable {
				socket.close()
				sessions.values.forEach { runCatching { it.close() } }
			}
		state.update { it + (forward.id to ForwardStatus(vmId, published, listening = true, temporary = temporary)) }
		thread(name = "forward-udp-${published.hostPort}", isDaemon = true) {
			val buffer = ByteArray(MAX_DATAGRAM)
			while (!socket.isClosed) {
				val packet = DatagramPacket(buffer, buffer.size)
				runCatching { socket.receive(packet) }.getOrNull() ?: break
				val from = packet.socketAddress
				if (!Firewall.allows(published, packet.address)) {
					count(forward.id) { it.copy(refused = it.refused + 1) }
					continue
				}
				val session =
					sessions[from] ?: runCatching { openUdp(guest, published.guestPort, socket, from, sessions) }.getOrNull()
				if (session == null) continue
				runCatching {
					synchronized(session) {
						session.writeShort(packet.length)
						session.write(packet.data, packet.offset, packet.length)
						session.flush()
					}
				}.onFailure { sessions.remove(from) }
			}
		}
		return published.hostPort
	}

	/** One UDP client's datagrams travel to the guest as length-prefixed frames over a stream to the agent. */
	private fun openUdp(
		guest: RunningGuest,
		guestPort: Int,
		socket: DatagramSocket,
		client: SocketAddress,
		sessions: MutableMap<SocketAddress, DataOutputStream>,
	): DataOutputStream {
		val connection = guest.connectUdp(guestPort)
		val output = DataOutputStream(connection.output)
		sessions[client] = output
		thread(name = "forward-udp-reply", isDaemon = true) {
			val input = DataInputStream(connection.input)
			try {
				while (true) {
					val length = input.readUnsignedShort()
					val data = ByteArray(length)
					input.readFully(data)
					socket.send(DatagramPacket(data, length, client))
				}
			} catch (_: IOException) {
			} finally {
				sessions.remove(client)
				connection.close()
			}
		}
		return output
	}

	private fun count(
		forwardId: String,
		change: (ForwardStatus) -> ForwardStatus,
	) {
		state.update { current -> current[forwardId]?.let { current + (forwardId to change(it)) } ?: current }
	}

	companion object {
		private const val TAG = "RuiForward"
		private const val BACKLOG = 50
		private const val MAX_DATAGRAM = 65_507

		/** Copies both ways until both sides close, then calls [done] once. */
		fun splice(
			input: InputStream,
			output: OutputStream,
			remote: GuestConnection,
			done: () -> Unit,
		) {
			val upstream =
				thread(name = "splice-up", isDaemon = true) {
					runCatching { input.copyTo(remote.output) }
					runCatching { remote.output.close() }
				}
			runCatching { remote.input.copyTo(output) }
			runCatching { output.close() }
			upstream.join(SPLICE_LINGER_MS)
			remote.close()
			done()
		}

		private const val SPLICE_LINGER_MS = 2000L
	}
}

/** Opens a UDP session to [guestPort] through the agent: `UDP <port>`, then length-prefixed datagrams. */
fun RunningGuest.connectUdp(guestPort: Int): GuestConnection = connectRaw("UDP $guestPort")

/** Backends that speak the agent protocol give [connect] for TCP; UDP goes through the same request line. */
fun RunningGuest.connectRaw(request: String): GuestConnection =
	(this as? AgentGuest)?.agentRequest(request) ?: throw IOException("UDP ports need Rui's guest agent")

/** A running guest whose connections go through rui-agent, so it can carry UDP too. */
interface AgentGuest {
	fun agentRequest(request: String): GuestConnection
}
