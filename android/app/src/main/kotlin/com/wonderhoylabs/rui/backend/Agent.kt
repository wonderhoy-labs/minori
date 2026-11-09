package com.wonderhoylabs.rui.backend

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

/**
 * The phone's half of rui-agent's protocol (see crates/rui-agent). Each request is one line on a fresh connection
 * to the agent; the transport (vsock for AVF, TCP for tests) is the caller's.
 */
object Agent {
	/** The vsock port rui-agent listens on in the guest. */
	const val VSOCK_PORT = 5741L

	/** Turns a fresh connection to the agent into a connection to [guestPort] in the guest. */
	fun connect(
		agent: GuestConnection,
		guestPort: Int,
	): GuestConnection {
		try {
			request(agent, "CONNECT $guestPort")
		} catch (error: IOException) {
			agent.close()
			throw error
		}
		return agent
	}

	fun hello(agent: GuestConnection): String = agent.use { request(it, "HELLO") }

	fun ports(agent: GuestConnection): List<Int> =
		agent.use { connection ->
			val reply = request(connection, "PORTS")
			reply
				.trim()
				.removePrefix("[")
				.removeSuffix("]")
				.split(",")
				.mapNotNull { it.trim().toIntOrNull() }
		}

	fun poweroff(agent: GuestConnection): Boolean = agent.use { runCatching { request(it, "POWEROFF") }.isSuccess }

	/** Sends one request line and returns what follows "OK", or throws with the agent's reason. */
	fun request(
		agent: GuestConnection,
		line: String,
	): String {
		agent.output.write("$line\n".toByteArray())
		agent.output.flush()
		val reply = readLine(agent.input) ?: throw IOException("The guest's agent closed the connection")
		return when {
			reply == "OK" -> ""
			reply.startsWith("OK ") -> reply.removePrefix("OK ")
			reply.startsWith("ERR ") -> throw IOException(reply.removePrefix("ERR "))
			else -> throw IOException("The guest's agent said something Rui doesn't understand")
		}
	}

	/** Reads one line byte by byte, so nothing after it is swallowed by a buffer. */
	private fun readLine(input: InputStream): String? {
		val line = ByteArrayOutputStream()
		while (line.size() < 1024) {
			val byte = input.read()
			if (byte < 0) return if (line.size() == 0) null else line.toString(Charsets.UTF_8)
			if (byte == '\n'.code) return line.toString(Charsets.UTF_8)
			line.write(byte)
		}
		throw IOException("The guest's agent sent a line that was too long")
	}
}
