package com.wonderhoylabs.minori.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BrandTest {
	@Test
	fun brandIsFilledIn() {
		assertEquals("Minori", Brand.NAME)
		assertTrue(Brand.CHARACTER.isNotBlank())
		assertTrue(Brand.REPOSITORY.startsWith("https://github.com/wonderhoy-labs/"))
	}
}
