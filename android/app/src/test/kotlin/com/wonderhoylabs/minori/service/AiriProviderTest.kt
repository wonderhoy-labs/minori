package com.wonderhoylabs.minori.service

import com.wonderhoylabs.minori.model.BackendKind
import com.wonderhoylabs.minori.model.VmConfig
import com.wonderhoylabs.minori.model.VmState
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AiriProviderTest {
	@Test
	fun listsMachinesAiriCanUseWithTheNameLast() {
		val ready = VmConfig(id = "a1", name = "Xfce, at home", agent = true, airi = true)
		val notYet = VmConfig(id = "b2", name = "Debian", agent = true)
		val noAgent = VmConfig(id = "c3", name = "Bare")
		val termux = VmConfig(id = "d4", name = "Termux", backend = BackendKind.TERMUX, distro = "debian", airi = true)
		val alpine = VmConfig(id = "e5", name = "Alpine", backend = BackendKind.TERMUX, distro = "alpine")
		val states = mapOf("a1" to VmState.Running, "b2" to VmState.Failed("No"))
		val rows = AiriProvider.rows(listOf(ready, notYet, noAgent, termux, alpine)) { states[it] ?: VmState.Stopped }
		assertArrayEquals(arrayOf("id", "state", "airi", "name"), AiriProvider.COLUMNS)
		assertEquals(3, rows.size)
		assertArrayEquals(arrayOf<Any>("a1", "running", 1, "Xfce, at home"), rows[0])
		assertArrayEquals(arrayOf<Any>("b2", "error", 0, "Debian"), rows[1])
		assertArrayEquals(arrayOf<Any>("d4", "stopped", 1, "Termux"), rows[2])
	}

	@Test
	fun findsTheMachineInAnAgentPath() {
		assertEquals("a1", AiriProvider.agentMachine(listOf("machines", "a1", "agent")))
		assertNull(AiriProvider.agentMachine(listOf("machines", "a1")))
		assertNull(AiriProvider.agentMachine(listOf("machines", "", "agent")))
		assertNull(AiriProvider.agentMachine(listOf("machines", "a1", "console")))
	}
}
