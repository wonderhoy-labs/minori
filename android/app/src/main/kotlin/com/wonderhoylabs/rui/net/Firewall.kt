package com.wonderhoylabs.rui.net

import com.wonderhoylabs.rui.model.PortForward
import java.net.InetAddress

/** An address or CIDR range, such as `192.168.1.0/24`, `10.0.0.5` or `fd00::/8`. */
class AddressRange private constructor(
	private val network: ByteArray,
	private val prefix: Int,
) {
	fun contains(address: InetAddress): Boolean {
		val bytes = normalise(address.address)
		if (bytes.size != network.size) return false
		var bits = prefix
		for (index in bytes.indices) {
			if (bits <= 0) return true
			val mask = if (bits >= 8) 0xff else (0xff shl (8 - bits)) and 0xff
			if ((bytes[index].toInt() and mask) != (network[index].toInt() and mask)) return false
			bits -= 8
		}
		return true
	}

	companion object {
		/** Parses a range, or returns null if it isn't one. Never looks anything up in DNS. */
		fun parse(text: String): AddressRange? {
			val trimmed = text.trim()
			if (trimmed.isEmpty()) return null
			val address = trimmed.substringBefore('/')
			if (!looksNumeric(address)) return null
			val bytes = runCatching { normalise(InetAddress.getByName(address).address) }.getOrNull() ?: return null
			val maxPrefix = bytes.size * 8
			val prefix =
				if ('/' in trimmed) {
					trimmed.substringAfter('/').toIntOrNull()?.takeIf { it in 0..maxPrefix } ?: return null
				} else {
					maxPrefix
				}
			return AddressRange(bytes, prefix)
		}

		private fun looksNumeric(address: String): Boolean =
			address.isNotEmpty() && address.all { it.isDigit() || it == '.' || it == ':' || it in 'a'..'f' || it in 'A'..'F' } &&
				(address.contains('.') || address.contains(':'))

		/**
		 * IPv4-mapped IPv6 addresses are compared as IPv4, so `::ffff:10.0.0.1` matches `10.0.0.0/8`, and IPv6
		 * loopback as 127.0.0.1, so a rule about this phone covers both.
		 */
		private fun normalise(bytes: ByteArray): ByteArray {
			if (bytes.size == 16 && bytes.take(15).all { it.toInt() == 0 } && bytes[15].toInt() == 1) return byteArrayOf(127, 0, 0, 1)
			if (bytes.size == 16 && bytes.take(10).all { it.toInt() == 0 } && bytes[10].toInt() == -1 && bytes[11].toInt() == -1) {
				return bytes.copyOfRange(12, 16)
			}
			return bytes
		}
	}
}

/** Decides who may connect to a published port. Deny always wins; an empty allow list allows everyone else. */
object Firewall {
	fun allows(
		forward: PortForward,
		address: InetAddress,
	): Boolean {
		if (forward.deny.mapNotNull(AddressRange::parse).any { it.contains(address) }) return false
		val allow = forward.allow.mapNotNull(AddressRange::parse)
		return allow.isEmpty() || allow.any { it.contains(address) }
	}

	/** The entries in [entries] that aren't valid ranges, to show next to the field. */
	fun invalid(entries: List<String>): List<String> = entries.filter { it.isNotBlank() && AddressRange.parse(it) == null }
}
