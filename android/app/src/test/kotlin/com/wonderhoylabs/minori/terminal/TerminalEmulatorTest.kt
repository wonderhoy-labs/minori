package com.wonderhoylabs.minori.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalEmulatorTest {
	private fun TerminalEmulator.feed(text: String) = feed(text.toByteArray())

	@Test
	fun printsAndWraps() {
		val terminal = TerminalEmulator(columns = 10, rows = 3)
		terminal.feed("hello\r\nabcdefghijkl")
		assertEquals("hello", terminal.row(0).text())
		assertEquals("abcdefghij", terminal.row(1).text())
		assertEquals("kl", terminal.row(2).text())
	}

	@Test
	fun scrollsIntoScrollback() {
		val terminal = TerminalEmulator(columns = 10, rows = 2)
		terminal.feed("one\r\ntwo\r\nthree")
		assertEquals("one", terminal.scrollback.last().text())
		assertEquals("two", terminal.row(0).text())
		assertEquals("three", terminal.row(1).text())
	}

	@Test
	fun movesTheCursorAndErases() {
		val terminal = TerminalEmulator(columns = 10, rows = 3)
		terminal.feed("xxxxxxxxxx\u001b[1;4H\u001b[K")
		assertEquals("xxx", terminal.row(0).text())
		terminal.feed("\u001b[2J\u001b[3;2Hz")
		assertEquals("", terminal.row(0).text())
		assertEquals(" z", terminal.row(2).text())
	}

	@Test
	fun readsColoursAndAttributes() {
		val terminal = TerminalEmulator(columns = 10, rows = 2)
		terminal.feed("\u001b[1;31mR\u001b[0m\u001b[38;5;200mP\u001b[38;2;1;2;3mT")
		val row = terminal.row(0)
		assertEquals(1, row.fg[0])
		assertTrue(row.attrs[0] and TerminalEmulator.BOLD != 0)
		assertEquals(200, row.fg[1])
		assertEquals(TerminalEmulator.TRUE_COLOUR or 0x010203, row.fg[2])
	}

	@Test
	fun switchesToTheAlternateScreenAndBack() {
		val terminal = TerminalEmulator(columns = 10, rows = 2)
		terminal.feed("shell")
		terminal.feed("\u001b[?1049h\u001b[Hfull")
		assertTrue(terminal.usingAlternateScreen)
		assertEquals("full", terminal.row(0).text())
		terminal.feed("\u001b[?1049l")
		assertFalse(terminal.usingAlternateScreen)
		assertEquals("shell", terminal.row(0).text())
	}

	@Test
	fun answersCursorAndSizeQueries() {
		val replies = StringBuilder()
		val terminal = TerminalEmulator(columns = 52, rows = 25, respond = { replies.append(String(it)) })
		terminal.feed("ab\u001b[6n\u001b[18t")
		assertEquals("\u001b[1;3R\u001b[8;25;52t", replies.toString())
	}

	@Test
	fun decodesUtf8() {
		val terminal = TerminalEmulator(columns = 10, rows = 1)
		terminal.feed("é→✓")
		assertEquals("é→✓", terminal.row(0).text())
	}

	@Test
	fun keepsScrollRegions() {
		val terminal = TerminalEmulator(columns = 5, rows = 4)
		terminal.feed("top\r\n\u001b[2;3r\u001b[2;1Ha\r\nb\r\nc")
		assertEquals("top", terminal.row(0).text())
		assertEquals("b", terminal.row(1).text())
		assertEquals("c", terminal.row(2).text())
	}
}
