package com.wonderhoylabs.rui.backend

import android.os.ParcelFileDescriptor
import com.wonderhoylabs.rui.core.Native
import com.wonderhoylabs.rui.model.BackendKind
import com.wonderhoylabs.rui.model.VmConfig
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.SequenceInputStream
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.concurrent.thread

/** Where the demo backend's console and guest ports go, for debug builds driven by tests. */
data class DemoTarget(
	/** `host:port` of a TCP serial console, or null for a shell on the phone. */
	val console: String? = null,
	/** `host:port` of a rui-agent in TCP mode, or null to treat the phone's own loopback as the guest's. */
	val agent: String? = null,
)

/**
 * Stands in for a hypervisor where there's none, so the whole app can be used and tested on the emulator. It is
 * not a VM: the console is a shell on the phone (or a TCP serial console a test points it at), and the guest's
 * ports are the phone's own loopback (or a rui-agent over TCP). Debug builds only, and always labelled.
 */
class DemoHypervisor(
	private val target: () -> DemoTarget,
) : Hypervisor {
	override val kind = BackendKind.DEMO

	override fun availability(): Availability = Availability.Ready

	override fun start(
		vm: VmConfig,
		dir: File,
		events: GuestEvents,
	): RunningGuest {
		val target = target()
		val console = target.console?.let { tcpConsole(it) } ?: shellConsole(vm, dir, events)
		return object : RunningGuest, com.wonderhoylabs.rui.net.AgentGuest {
			override val console = console

			override fun agentRequest(request: String): GuestConnection {
				val agent = target.agent ?: throw java.io.IOException("UDP ports in the demo need a rui-agent to point at")
				val connection = tcp(agent)
				try {
					Agent.request(connection, request)
				} catch (error: java.io.IOException) {
					connection.close()
					throw error
				}
				return connection
			}
			override val log: InputStream? = null

			override fun connect(guestPort: Int): GuestConnection {
				val agent = target.agent ?: return tcp("127.0.0.1:$guestPort")
				return Agent.connect(tcp(agent), guestPort)
			}

			override fun listeningPorts(): List<Int>? = target.agent?.let { Agent.ports(tcp(it)) }

			override fun requestShutdown(): Boolean {
				forceStop()
				return true
			}

			override fun forceStop() {
				console.close()
				events.onStopped(null)
			}
		}
	}

	private fun shellConsole(
		vm: VmConfig,
		dir: File,
		events: GuestEvents,
	): ConsoleChannel {
		val home = File(dir, "home").apply { mkdirs() }
		val environment =
			arrayOf(
				"HOME=${home.path}",
				"TERM=xterm-256color",
				"PATH=/system/bin:/system/xbin",
				"PS1=${vm.name.replace(' ', '-').lowercase()}\$ ",
			)
		val (fd, pid) = Native.ptySpawn("/system/bin/sh", arrayOf("-i"), environment, home.path, 24, 80).let { it[0].toInt() to it[1].toInt() }
		val terminal = ParcelFileDescriptor.adoptFd(fd)
		val banner =
			"\u001b[1mRui demo backend\u001b[0m: this is a shell on the phone, not a virtual machine.\r\n" +
				"It lets you try Rui's console where Android's Virtualization Framework isn't available.\r\n\r\n"
		thread(name = "demo-wait-$pid", isDaemon = true) {
			val status = Native.ptyWait(pid)
			events.onStopped(if (status == 0) null else "The shell exited with status $status")
		}
		return object : ConsoleChannel {
			override val output: InputStream =
				SequenceInputStream(ByteArrayInputStream(banner.toByteArray()), FileInputStream(terminal.fileDescriptor))
			override val input: OutputStream = FileOutputStream(terminal.fileDescriptor)

			override fun resize(
				rows: Int,
				cols: Int,
			) = Native.ptyResize(terminal.fd, rows, cols)

			override fun close() {
				Native.ptyKill(pid, SIGHUP)
				runCatching { terminal.close() }
			}
		}
	}

	private fun tcpConsole(address: String): ConsoleChannel {
		val socket = tcpSocket(address)
		return object : ConsoleChannel {
			override val output: InputStream = socket.getInputStream()
			override val input: OutputStream = socket.getOutputStream()

			override fun close() = socket.close()
		}
	}

	companion object {
		private const val SIGHUP = 1
		private const val CONNECT_TIMEOUT_MS = 5000

		fun tcpSocket(address: String): Socket {
			val host = address.substringBeforeLast(':')
			val port = address.substringAfterLast(':').toInt()
			return Socket().apply {
				tcpNoDelay = true
				connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
			}
		}

		fun tcp(address: String): GuestConnection {
			val socket = tcpSocket(address)
			return object : GuestConnection {
				override val input: InputStream = socket.getInputStream()
				override val output: OutputStream = socket.getOutputStream()

				override fun close() = socket.close()
			}
		}
	}
}
