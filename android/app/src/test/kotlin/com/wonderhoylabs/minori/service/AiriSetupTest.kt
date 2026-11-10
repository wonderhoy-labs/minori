package com.wonderhoylabs.minori.service

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
	fun knowsAMachineThatGivesAiriItsPort() {
		val plain = VmConfig(name = "Debian")
		assertFalse(AiriSetup.isSetUp(plain))
		val forward = PortForward(hostPort = AiriSetup.PORT, guestPort = AiriSetup.PORT)
		assertTrue(AiriSetup.isSetUp(plain.copy(network = NetworkSettings(forwards = listOf(forward)))))
	}
}
