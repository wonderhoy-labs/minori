package com.wonderhoylabs.minori.service

import com.wonderhoylabs.minori.ui.screens.memoryWarning
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
	fun everyTemplateIsOfficialAndChecked() {
		for (template in Catalog.templates) {
			val base = template.baseImage
			assertTrue(base.url.startsWith("https://"))
			assertTrue(base.checksums.startsWith("https://"))
			assertTrue(base.url.endsWith(base.file))
		}
	}

	@Test
	fun onlyListsWhatRunsOnAPhone() {
		val ids = Catalog.templates.map { it.id }
		assertTrue(ids.none { "windows" in it || "arch" in it })
		assertEquals(ids.size, ids.toSet().size)
	}

	@Test
	fun desktopsSetUpTheirServersAfterTheirPackages() {
		for (template in Catalog.templates.filter { it.vncPort != null }) {
			assertTrue(template.packages.contains("tigervnc-standalone-server"))
			assertTrue(template.run.any { "tigervncserver@:1" in it })
		}
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
