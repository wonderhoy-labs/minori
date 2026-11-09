package com.wonderhoylabs.rui.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BrandTest {
	@Test
	fun brandIsFilledIn() {
		assertEquals("Template", Brand.NAME)
		assertTrue(Brand.CHARACTER.isNotBlank())
		assertTrue(Brand.REPOSITORY.startsWith("https://github.com/wonderhoy-labs/"))
	}
}
