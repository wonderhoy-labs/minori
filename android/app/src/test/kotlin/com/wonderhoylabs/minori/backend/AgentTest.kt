package com.wonderhoylabs.minori.backend

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException

class AgentTest {
	private class Fake(reply: String) : GuestConnection {
		override val input = ByteArrayInputStream(reply.toByteArray())
		override val output = ByteArrayOutputStream()

		override fun close() {}
	}

	@Test
	fun connectSendsTheRequestAndLeavesTheStream() {
		val fake = Fake("OK\nSSH-2.0-OpenSSH")
		Agent.connect(fake, 22)
		assertEquals("CONNECT 22\n", fake.output.toString())
		assertEquals("SSH-2.0-OpenSSH", fake.input.readBytes().toString(Charsets.UTF_8))
	}

	@Test
	fun parsesListeningPorts() {
		assertEquals(listOf(22, 5901), Agent.ports(Fake("OK [22,5901]\n")))
	}

	@Test
	fun reportsTheAgentsError() {
		val error = assertThrows(IOException::class.java) { Agent.connect(Fake("ERR Connection refused\n"), 80) }
		assertEquals("Connection refused", error.message)
	}
}
