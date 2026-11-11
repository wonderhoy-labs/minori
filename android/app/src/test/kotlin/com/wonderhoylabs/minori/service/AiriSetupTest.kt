package com.wonderhoylabs.minori.service

import com.wonderhoylabs.minori.model.BackendKind
import com.wonderhoylabs.minori.model.NetworkSettings
import com.wonderhoylabs.minori.model.PortForward
import com.wonderhoylabs.minori.model.VmConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AiriSetupTest {
	private val token = "0123456789abcdef".repeat(4)

	@Test
	fun findsTheTokenInAirisCommand() {
		assertEquals(token, AiriSetup.token(" sh /mnt/shared/Download/Airi-1a2b3c/install.sh $token"))
		assertEquals(token, AiriSetup.token(token.uppercase()))
	}

	@Test
	fun refusesAnythingThatIsntAToken() {
		assertNull(AiriSetup.token("sh install.sh"))
		assertNull(AiriSetup.token("sh install.sh ${token.dropLast(1)}"))
		assertNull(AiriSetup.token("sh install.sh ${token}0"))
	}

	@Test
	fun needsMinorisAgentAndNoTermux() {
		assertFalse(AiriSetup.canSetUp(VmConfig(name = "Debian")))
		assertTrue(AiriSetup.canSetUp(VmConfig(name = "Debian", agent = true)))
		assertFalse(AiriSetup.canSetUp(VmConfig(name = "Debian", agent = true, backend = BackendKind.TERMUX)))
	}

	@Test
	fun knowsAMachineAiriIsSetUpIn() {
		val plain = VmConfig(name = "Debian", agent = true)
		assertFalse(AiriSetup.isSetUp(plain))
		assertTrue(AiriSetup.isSetUp(plain.copy(airi = true)))
		assertFalse(AiriSetup.isSetUp(plain.copy(airi = true, agent = false)))
	}

	@Test
	fun movesMinori02sPhonePortToTheFlag() {
		val legacy = PortForward(label = "Airi", hostPort = AiriSetup.PORT, guestPort = AiriSetup.PORT)
		val ssh = PortForward(label = "SSH", hostPort = 2222, guestPort = 22)
		val vm = VmConfig(name = "Debian", agent = true, network = NetworkSettings(forwards = listOf(ssh, legacy)))
		assertTrue(AiriSetup.isSetUp(vm))
		val moved = AiriSetup.markSetUp(vm)
		assertTrue(moved.airi)
		assertEquals(listOf(ssh), moved.network.forwards)
		assertTrue(AiriSetup.isSetUp(moved))
	}

	@Test
	fun leavesAPortTheUserPublishedThemselves() {
		val own = PortForward(label = "Mine", hostPort = AiriSetup.PORT, guestPort = AiriSetup.PORT)
		val vm = VmConfig(name = "Debian", agent = true, network = NetworkSettings(forwards = listOf(own)))
		assertFalse(AiriSetup.isSetUp(vm))
		assertEquals(listOf(own), AiriSetup.markSetUp(vm).network.forwards)
	}
}
