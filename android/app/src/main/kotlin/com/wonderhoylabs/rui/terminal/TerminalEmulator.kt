package com.wonderhoylabs.rui.terminal

/**
 * An xterm-compatible screen, enough for a Linux login, shells, `top`, `htop`, `nano` and `vim`: cursor movement,
 * erasing, scroll regions, insert and delete, the alternate screen, 16, 256 and 24-bit colour, and the replies to
 * cursor and device queries. It keeps scrollback, and changes [revision] whenever the screen changes.
 */
class TerminalEmulator(
	columns: Int = 80,
	rows: Int = 24,
	private val respond: (ByteArray) -> Unit = {},
) {
	class Row(
		columns: Int,
	) {
		val chars = IntArray(columns) { ' '.code }
		val fg = IntArray(columns) { DEFAULT }
		val bg = IntArray(columns) { DEFAULT }
		val attrs = IntArray(columns)

		fun copyFrom(other: Row) {
			val count = minOf(chars.size, other.chars.size)
			other.chars.copyInto(chars, 0, 0, count)
			other.fg.copyInto(fg, 0, 0, count)
			other.bg.copyInto(bg, 0, 0, count)
			other.attrs.copyInto(attrs, 0, 0, count)
		}

		fun text(): String = buildString { chars.forEach { appendCodePoint(it) } }.trimEnd()
	}

	var columns = columns
		private set
	var rows = rows
		private set

	private var main = Array(rows) { Row(columns) }
	private var alt = Array(rows) { Row(columns) }
	private var screen = main
	val scrollback = ArrayDeque<Row>()

	var cursorRow = 0
		private set
	var cursorCol = 0
		private set
	var cursorVisible = true
		private set
	var applicationCursorKeys = false
		private set
	var bracketedPaste = false
		private set
	val usingAlternateScreen: Boolean get() = screen === alt

	/** Changes on every update, so a view can redraw. */
	@Volatile var revision = 0L
		private set

	@Volatile var title = ""
		private set

	private var fg = DEFAULT
	private var bg = DEFAULT
	private var attrs = 0
	private var scrollTop = 0
	private var scrollBottom = rows - 1
	private var wrapPending = false
	private var autowrap = true
	private var originMode = false
	private var insertMode = false
	private var saved = IntArray(5)
	private var tabStops = BooleanArray(columns) { it % 8 == 0 }

	private var state = State.Ground
	private val params = ArrayList<Int>()
	private var param = -1
	private var privateMarker = 0.toChar()
	private var intermediate = 0.toChar()
	private val osc = StringBuilder()
	private var utf8Remaining = 0
	private var utf8Code = 0

	private enum class State {
		Ground,
		Escape,
		Csi,
		Osc,
		OscEscape,
		Charset,
	}

	fun row(index: Int): Row = screen[index]

	/** All rows of scrollback then the screen, top to bottom, for selecting and copying. */
	fun allText(): String = (scrollback.map { it.text() } + screen.map { it.text() }).joinToString("\n").trimEnd()

	@Synchronized
	fun feed(
		data: ByteArray,
		offset: Int = 0,
		length: Int = data.size,
	) {
		for (index in offset until offset + length) {
			val byte = data[index].toInt() and 0xff
			if (utf8Remaining > 0) {
				if (byte and 0xc0 == 0x80) {
					utf8Code = (utf8Code shl 6) or (byte and 0x3f)
					utf8Remaining--
					if (utf8Remaining == 0) accept(utf8Code)
					continue
				}
				utf8Remaining = 0
				accept(REPLACEMENT)
			}
			when {
				byte < 0x80 -> accept(byte)
				byte and 0xe0 == 0xc0 -> start(byte and 0x1f, 1)
				byte and 0xf0 == 0xe0 -> start(byte and 0x0f, 2)
				byte and 0xf8 == 0xf0 -> start(byte and 0x07, 3)
				else -> accept(REPLACEMENT)
			}
		}
		revision++
	}

	private fun start(
		bits: Int,
		more: Int,
	) {
		utf8Code = bits
		utf8Remaining = more
	}

	private fun accept(code: Int) {
		when (state) {
			State.Ground -> ground(code)
			State.Escape -> escape(code)
			State.Csi -> csi(code)
			State.Osc ->
				when (code) {
					BEL -> endOsc()
					ESC -> state = State.OscEscape
					else -> if (osc.length < MAX_OSC) osc.appendCodePoint(code)
				}
			State.OscEscape -> {
				endOsc()
				if (code != '\\'.code) escape(code)
			}
			State.Charset -> state = State.Ground
		}
	}

	private fun ground(code: Int) {
		when (code) {
			ESC -> state = State.Escape
			'\r'.code -> {
				cursorCol = 0
				wrapPending = false
			}
			'\n'.code, 0x0b, 0x0c -> lineFeed()
			'\b'.code -> {
				if (cursorCol > 0) cursorCol--
				wrapPending = false
			}
			'\t'.code -> tab()
			BEL, 0x0e, 0x0f, 0 -> {}
			else -> if (code >= 0x20 && code != 0x7f) print(code)
		}
	}

	private fun print(code: Int) {
		if (wrapPending && autowrap) {
			cursorCol = 0
			lineFeed()
		}
		wrapPending = false
		val row = screen[cursorRow]
		if (insertMode) {
			for (column in columns - 1 downTo cursorCol + 1) moveCell(row, column - 1, row, column)
		}
		row.chars[cursorCol] = code
		row.fg[cursorCol] = fg
		row.bg[cursorCol] = bg
		row.attrs[cursorCol] = attrs
		if (cursorCol == columns - 1) wrapPending = true else cursorCol++
	}

	private fun moveCell(
		from: Row,
		fromCol: Int,
		to: Row,
		toCol: Int,
	) {
		to.chars[toCol] = from.chars[fromCol]
		to.fg[toCol] = from.fg[fromCol]
		to.bg[toCol] = from.bg[fromCol]
		to.attrs[toCol] = from.attrs[fromCol]
	}

	private fun lineFeed() {
		if (cursorRow == scrollBottom) scrollUp(1) else if (cursorRow < rows - 1) cursorRow++
		wrapPending = false
	}

	private fun reverseIndex() {
		if (cursorRow == scrollTop) scrollDown(1) else if (cursorRow > 0) cursorRow--
		wrapPending = false
	}

	private fun tab() {
		var column = cursorCol + 1
		while (column < columns - 1 && !tabStops[column]) column++
		cursorCol = column.coerceAtMost(columns - 1)
		wrapPending = false
	}

	private fun blank(row: Row) {
		row.chars.fill(' '.code)
		row.fg.fill(DEFAULT)
		row.bg.fill(bg)
		row.attrs.fill(0)
	}

	private fun scrollUp(count: Int) {
		repeat(count.coerceAtMost(scrollBottom - scrollTop + 1)) {
			val top = screen[scrollTop]
			if (screen === main && scrollTop == 0) {
				scrollback.addLast(Row(columns).also { it.copyFrom(top) })
				if (scrollback.size > MAX_SCROLLBACK) scrollback.removeFirst()
			}
			for (index in scrollTop until scrollBottom) screen[index] = screen[index + 1]
			screen[scrollBottom] = top.also { blank(it) }
		}
	}

	private fun scrollDown(count: Int) {
		repeat(count.coerceAtMost(scrollBottom - scrollTop + 1)) {
			val bottom = screen[scrollBottom]
			for (index in scrollBottom downTo scrollTop + 1) screen[index] = screen[index - 1]
			screen[scrollTop] = bottom.also { blank(it) }
		}
	}

	private fun escape(code: Int) {
		state = State.Ground
		when (code.toChar()) {
			'[' -> {
				state = State.Csi
				params.clear()
				param = -1
				privateMarker = 0.toChar()
				intermediate = 0.toChar()
			}
			']' -> {
				state = State.Osc
				osc.setLength(0)
			}
			'(', ')', '*', '+' -> state = State.Charset
			'7' -> saveCursor()
			'8' -> restoreCursor()
			'D' -> lineFeed()
			'E' -> {
				cursorCol = 0
				lineFeed()
			}
			'M' -> reverseIndex()
			'H' -> tabStops[cursorCol] = true
			'c' -> reset()
			'=', '>' -> {}
			else -> {}
		}
	}

	private fun endOsc() {
		state = State.Ground
		val text = osc.toString()
		val separator = text.indexOf(';')
		if (separator > 0) {
			val command = text.substring(0, separator)
			if (command == "0" || command == "2") title = text.substring(separator + 1)
		}
	}

	private fun csi(code: Int) {
		val char = code.toChar()
		when {
			char in '0'..'9' -> param = (if (param < 0) 0 else param) * 10 + (code - '0'.code)
			char == ';' || char == ':' -> {
				params.add(param)
				param = -1
			}
			char == '?' || char == '>' || char == '<' || char == '=' -> privateMarker = char
			char == ' ' || char == '!' || char == '"' || char == '$' || char == '\'' -> intermediate = char
			code in 0x40..0x7e -> {
				params.add(param)
				state = State.Ground
				dispatch(char)
			}
			code == ESC -> state = State.Escape
			code < 0x20 -> ground(code)
			else -> state = State.Ground
		}
	}

	private fun arg(
		index: Int,
		default: Int = 1,
	): Int {
		val value = params.getOrNull(index) ?: -1
		return if (value <= 0) default else value
	}

	private fun dispatch(command: Char) {
		if (privateMarker == '?') {
			when (command) {
				'h' -> params.forEach { setMode(it, true) }
				'l' -> params.forEach { setMode(it, false) }
			}
			return
		}
		if (privateMarker == '>' || privateMarker == '=' || intermediate != 0.toChar()) {
			if (command == 'c' && privateMarker == '>') reply("\u001b[>0;10;1c")
			if (command == 'p' && intermediate == '!') reset()
			return
		}
		when (command) {
			'A' -> moveTo(cursorRow - arg(0), cursorCol)
			'B', 'e' -> moveTo(cursorRow + arg(0), cursorCol)
			'C', 'a' -> moveTo(cursorRow, cursorCol + arg(0))
			'D' -> moveTo(cursorRow, cursorCol - arg(0))
			'E' -> moveTo(cursorRow + arg(0), 0)
			'F' -> moveTo(cursorRow - arg(0), 0)
			'G', '`' -> moveTo(cursorRow, arg(0) - 1)
			'd' -> moveTo((if (originMode) scrollTop else 0) + arg(0) - 1, cursorCol)
			'H', 'f' -> moveTo((if (originMode) scrollTop else 0) + arg(0) - 1, arg(1) - 1)
			'J' -> eraseDisplay(arg(0, 0))
			'K' -> eraseLine(arg(0, 0))
			'L' -> if (cursorRow in scrollTop..scrollBottom) withRegion(cursorRow) { scrollDown(arg(0)) }
			'M' -> if (cursorRow in scrollTop..scrollBottom) withRegion(cursorRow) { scrollUp(arg(0)) }
			'@' -> insertChars(arg(0))
			'P' -> deleteChars(arg(0))
			'X' -> eraseChars(arg(0))
			'S' -> scrollUp(arg(0))
			'T' -> scrollDown(arg(0))
			'm' -> sgr()
			'r' -> {
				val top = arg(0) - 1
				val bottom = (params.getOrNull(1)?.takeIf { it > 0 } ?: rows) - 1
				if (top < bottom && bottom < rows) {
					scrollTop = top
					scrollBottom = bottom
					moveTo(if (originMode) top else 0, 0)
				}
			}
			's' -> saveCursor()
			'u' -> restoreCursor()
			'h' -> if (arg(0, 0) == 4) insertMode = true
			'l' -> if (arg(0, 0) == 4) insertMode = false
			'n' ->
				when (arg(0, 0)) {
					5 -> reply("\u001b[0n")
					6 -> reply("\u001b[${cursorRow + 1};${cursorCol + 1}R")
				}
			'c' -> reply("\u001b[?62;22c")
			'g' ->
				when (arg(0, 0)) {
					0 -> tabStops[cursorCol] = false
					3 -> tabStops.fill(false)
				}
			't' -> {}
			else -> {}
		}
	}

	private inline fun withRegion(
		top: Int,
		action: () -> Unit,
	) {
		val savedTop = scrollTop
		scrollTop = top
		action()
		scrollTop = savedTop
		cursorCol = 0
		wrapPending = false
	}

	private fun setMode(
		mode: Int,
		on: Boolean,
	) {
		when (mode) {
			1 -> applicationCursorKeys = on
			6 -> {
				originMode = on
				moveTo(if (on) scrollTop else 0, 0)
			}
			7 -> autowrap = on
			25 -> cursorVisible = on
			47, 1047 -> switchScreen(on, clear = mode == 1047)
			1048 -> if (on) saveCursor() else restoreCursor()
			1049 -> {
				if (on) saveCursor()
				switchScreen(on, clear = true)
				if (!on) restoreCursor()
			}
			2004 -> bracketedPaste = on
		}
	}

	private fun switchScreen(
		alternate: Boolean,
		clear: Boolean,
	) {
		screen = if (alternate) alt else main
		if (alternate && clear) alt.forEach { blank(it) }
		scrollTop = 0
		scrollBottom = rows - 1
	}

	private fun moveTo(
		row: Int,
		col: Int,
	) {
		val top = if (originMode) scrollTop else 0
		val bottom = if (originMode) scrollBottom else rows - 1
		cursorRow = row.coerceIn(top, bottom)
		cursorCol = col.coerceIn(0, columns - 1)
		wrapPending = false
	}

	private fun eraseDisplay(mode: Int) {
		when (mode) {
			0 -> {
				eraseLine(0)
				for (index in cursorRow + 1 until rows) blank(screen[index])
			}
			1 -> {
				eraseLine(1)
				for (index in 0 until cursorRow) blank(screen[index])
			}
			2 -> screen.forEach { blank(it) }
			3 -> scrollback.clear()
		}
	}

	private fun eraseLine(mode: Int) {
		val row = screen[cursorRow]
		val range =
			when (mode) {
				0 -> cursorCol until columns
				1 -> 0..cursorCol
				else -> 0 until columns
			}
		for (column in range) clearCell(row, column)
		wrapPending = false
	}

	private fun clearCell(
		row: Row,
		column: Int,
	) {
		row.chars[column] = ' '.code
		row.fg[column] = DEFAULT
		row.bg[column] = bg
		row.attrs[column] = 0
	}

	private fun insertChars(count: Int) {
		val row = screen[cursorRow]
		val n = count.coerceAtMost(columns - cursorCol)
		for (column in columns - 1 downTo cursorCol + n) moveCell(row, column - n, row, column)
		for (column in cursorCol until cursorCol + n) clearCell(row, column)
	}

	private fun deleteChars(count: Int) {
		val row = screen[cursorRow]
		val n = count.coerceAtMost(columns - cursorCol)
		for (column in cursorCol until columns - n) moveCell(row, column + n, row, column)
		for (column in columns - n until columns) clearCell(row, column)
	}

	private fun eraseChars(count: Int) {
		val row = screen[cursorRow]
		for (column in cursorCol until (cursorCol + count).coerceAtMost(columns)) clearCell(row, column)
	}

	private fun sgr() {
		var index = 0
		if (params.size == 1 && params[0] < 0) {
			resetStyle()
			return
		}
		while (index < params.size) {
			when (val code = params[index].coerceAtLeast(0)) {
				0 -> resetStyle()
				1 -> attrs = attrs or BOLD
				2 -> attrs = attrs or DIM
				3 -> attrs = attrs or ITALIC
				4 -> attrs = attrs or UNDERLINE
				7 -> attrs = attrs or INVERSE
				8 -> attrs = attrs or HIDDEN
				9 -> attrs = attrs or STRIKE
				21, 22 -> attrs = attrs and (BOLD or DIM).inv()
				23 -> attrs = attrs and ITALIC.inv()
				24 -> attrs = attrs and UNDERLINE.inv()
				27 -> attrs = attrs and INVERSE.inv()
				28 -> attrs = attrs and HIDDEN.inv()
				29 -> attrs = attrs and STRIKE.inv()
				in 30..37 -> fg = code - 30
				39 -> fg = DEFAULT
				in 40..47 -> bg = code - 40
				49 -> bg = DEFAULT
				in 90..97 -> fg = code - 90 + 8
				in 100..107 -> bg = code - 100 + 8
				38, 48 -> {
					val (colour, used) = extendedColour(index + 1)
					if (code == 38) fg = colour ?: fg else bg = colour ?: bg
					index += used
				}
			}
			index++
		}
	}

	private fun extendedColour(at: Int): Pair<Int?, Int> =
		when (params.getOrNull(at)) {
			5 -> (params.getOrNull(at + 1)?.coerceIn(0, 255)) to 2
			2 -> {
				val red = params.getOrNull(at + 1)?.coerceIn(0, 255) ?: 0
				val green = params.getOrNull(at + 2)?.coerceIn(0, 255) ?: 0
				val blue = params.getOrNull(at + 3)?.coerceIn(0, 255) ?: 0
				(TRUE_COLOUR or (red shl 16) or (green shl 8) or blue) to 4
			}
			else -> null to 0
		}

	private fun resetStyle() {
		fg = DEFAULT
		bg = DEFAULT
		attrs = 0
	}

	private fun saveCursor() {
		saved = intArrayOf(cursorRow, cursorCol, fg, bg, attrs)
	}

	private fun restoreCursor() {
		cursorRow = saved[0].coerceIn(0, rows - 1)
		cursorCol = saved[1].coerceIn(0, columns - 1)
		fg = saved[2]
		bg = saved[3]
		attrs = saved[4]
		wrapPending = false
	}

	private fun reset() {
		resetStyle()
		main.forEach { blank(it) }
		alt.forEach { blank(it) }
		screen = main
		scrollTop = 0
		scrollBottom = rows - 1
		cursorRow = 0
		cursorCol = 0
		cursorVisible = true
		applicationCursorKeys = false
		bracketedPaste = false
		autowrap = true
		originMode = false
		insertMode = false
	}

	private fun reply(text: String) = respond(text.toByteArray())

	/** Changes the size, keeping what fits, as xterm does. */
	@Synchronized
	fun resize(
		newColumns: Int,
		newRows: Int,
	) {
		if (newColumns == columns && newRows == rows) return
		if (newColumns < 2 || newRows < 2) return
		fun remake(old: Array<Row>): Array<Row> {
			val shift = (cursorRow - (newRows - 1)).coerceAtLeast(0)
			if (old === main && shift > 0) {
				for (index in 0 until shift) scrollback.addLast(Row(newColumns).also { it.copyFrom(old[index]) })
			}
			return Array(newRows) { index ->
				Row(newColumns).also { row -> old.getOrNull(index + shift)?.let { row.copyFrom(it) } }
			}
		}
		val shift = (cursorRow - (newRows - 1)).coerceAtLeast(0)
		val wasAlt = screen === alt
		main = remake(main)
		alt = remake(alt)
		screen = if (wasAlt) alt else main
		columns = newColumns
		rows = newRows
		cursorRow = (cursorRow - shift).coerceIn(0, rows - 1)
		cursorCol = cursorCol.coerceIn(0, columns - 1)
		scrollTop = 0
		scrollBottom = rows - 1
		tabStops = BooleanArray(columns) { it % 8 == 0 }
		wrapPending = false
		revision++
	}

	companion object {
		const val DEFAULT = -1
		const val TRUE_COLOUR = 1 shl 24
		const val BOLD = 1
		const val UNDERLINE = 2
		const val INVERSE = 4
		const val DIM = 8
		const val ITALIC = 16
		const val HIDDEN = 32
		const val STRIKE = 64
		const val MAX_SCROLLBACK = 3000
		private const val ESC = 0x1b
		private const val BEL = 0x07
		private const val MAX_OSC = 512
		private const val REPLACEMENT = 0xfffd
	}
}
