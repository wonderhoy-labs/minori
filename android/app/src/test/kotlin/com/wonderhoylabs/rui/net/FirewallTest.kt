package com.wonderhoylabs.rui.net

import com.wonderhoylabs.rui.model.PortForward
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

class FirewallTest {
	private fun address(text: String) = InetAddress.getByName(text)

	private fun forward(
		allow: List<String> = emptyList(),
		deny: List<String> = emptyList(),
	) = PortForward(hostPort = 2222, guestPort = 22, allow = allow, deny = deny)

	@Test
	fun allowsEveryoneWithoutRules() {
		assertTrue(Firewall.allows(forward(), address("192.168.1.20")))
	}

	@Test
	fun allowListLetsOnlyItsRangesIn() {
		val rule = forward(allow = listOf("192.168.1.0/24"))
		assertTrue(Firewall.allows(rule, address("192.168.1.20")))
		assertFalse(Firewall.allows(rule, address("192.168.2.20")))
	}

	@Test
	fun denyWinsOverAllow() {
		val rule = forward(allow = listOf("10.0.0.0/8"), deny = listOf("10.0.0.5"))
		assertTrue(Firewall.allows(rule, address("10.0.0.4")))
		assertFalse(Firewall.allows(rule, address("10.0.0.5")))
	}

	@Test
	fun ipv6LoopbackCountsAsThisPhone() {
		// Android's adb forward and many apps reach a loopback port from ::1.
		assertFalse(Firewall.allows(forward(deny = listOf("127.0.0.1")), address("::1")))
		assertTrue(Firewall.allows(forward(allow = listOf("127.0.0.0/8")), address("::1")))
	}

	@Test
	fun mappedAddressesMatchIpv4Ranges() {
		assertFalse(Firewall.allows(forward(deny = listOf("10.0.0.0/8")), address("::ffff:10.1.2.3")))
	}

	@Test
	fun ipv6RangesWork() {
		val rule = forward(allow = listOf("fd00::/8"))
		assertTrue(Firewall.allows(rule, address("fd12::1")))
		assertFalse(Firewall.allows(rule, address("2001:db8::1")))
	}

	@Test
	fun refusesHostNamesAndNonsense() {
		assertNull(AddressRange.parse("example.com"))
		assertNull(AddressRange.parse("10.0.0.0/33"))
		assertEquals(listOf("nope"), Firewall.invalid(listOf("10.0.0.1", "nope", " ")))
	}
}
