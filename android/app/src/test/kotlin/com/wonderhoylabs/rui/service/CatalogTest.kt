package com.wonderhoylabs.rui.service

import com.wonderhoylabs.rui.ui.screens.memoryWarning
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogTest {
	@Test
	fun readsGnuAndBsdChecksumLines() {
		assertEquals("abc123", Catalog.checksumFor("abc123  debian.qcow2", "debian.qcow2"))
		assertEquals("abc123", Catalog.checksumFor("abc123 *ubuntu.img", "ubuntu.img"))
		assertEquals("def456", Catalog.checksumFor("SHA256 (Fedora.qcow2) = def456", "Fedora.qcow2"))
		assertNull(Catalog.checksumFor("abc123  other.qcow2", "debian.qcow2"))
	}

	@Test
	fun everyTemplateIsOfficialOrExplainsWhyNot() {
		for (template in Catalog.templates) {
			val base = template.baseImage
			if (base == null) {
				assertNotNull("${template.id} needs a reason", template.unavailable)
			} else {
				assertTrue(base.url.startsWith("https://"))
				assertTrue(base.checksums.startsWith("https://"))
			}
		}
	}

	@Test
	fun windowsIsMarkedUnsupported() {
		assertNotNull(Catalog.templates.first { it.id == "windows" }.unavailable)
	}

	@Test
	fun warnsAboutMemoryThePhoneCantSpare() {
		assertNull(memoryWarning(2048, 16 * 1024))
		assertNotNull(memoryWarning(10 * 1024, 16 * 1024))
		assertNotNull(memoryWarning(128, 16 * 1024))
	}

	@Test
	fun passwordsAreReadable() {
		assertTrue(Operations.generatePassword().matches(Regex("[a-z2-9]{4}(-[a-z2-9]{4}){3}")))
	}
}
