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
		val termux = VmConfig(id = "d4", name = "Termux", agent = true, backend = BackendKind.TERMUX)
		val states = mapOf("a1" to VmState.Running, "b2" to VmState.Failed("No"))
		val rows = AiriProvider.rows(listOf(ready, notYet, noAgent, termux)) { states[it] ?: VmState.Stopped }
		assertArrayEquals(arrayOf("id", "state", "airi", "name"), AiriProvider.COLUMNS)
		assertEquals(2, rows.size)
		assertArrayEquals(arrayOf<Any>("a1", "running", 1, "Xfce, at home"), rows[0])
		assertArrayEquals(arrayOf<Any>("b2", "error", 0, "Debian"), rows[1])
	}

	@Test
	fun findsTheMachineInAnAgentPath() {
		assertEquals("a1", AiriProvider.agentMachine(listOf("machines", "a1", "agent")))
		assertNull(AiriProvider.agentMachine(listOf("machines", "a1")))
		assertNull(AiriProvider.agentMachine(listOf("machines", "", "agent")))
		assertNull(AiriProvider.agentMachine(listOf("machines", "a1", "console")))
	}
}
