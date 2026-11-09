package com.wonderhoylabs.rui.terminal

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.utf16CodePoint
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** The 16 colours, as xterm draws them, so guest programs look the way their authors expect. */
private val PALETTE =
	intArrayOf(
		0x000000,
		0xcd3131,
		0x0dbc79,
		0xe5e510,
		0x2472c8,
		0xbc3fbc,
		0x11a8cd,
		0xe5e5e5,
		0x666666,
		0xf14c4c,
		0x23d18b,
		0xf5f543,
		0x3b8eea,
		0xd670d6,
		0x29b8db,
		0xffffff,
	)

private fun colour(value: Int, default: Color): Color {
	if (value == TerminalEmulator.DEFAULT) return default
	if (value and TerminalEmulator.TRUE_COLOUR != 0) return Color(0xff000000.toInt() or (value and 0xffffff))
	val rgb =
		when (value) {
			in 0..15 -> PALETTE[value]

			in 16..231 -> {
				val index = value - 16
				val steps = intArrayOf(0, 95, 135, 175, 215, 255)
				(steps[index / 36] shl 16) or (steps[(index / 6) % 6] shl 8) or steps[index % 6]
			}

			else -> {
				val grey = 8 + (value - 232) * 10
				(grey shl 16) or (grey shl 8) or grey
			}
		}
	return Color(0xff000000.toInt() or rgb)
}

/**
 * Draws a [TerminalSession] and types into it. A hidden text field takes the soft keyboard's input; the row
 * of keys under the screen has what phone keyboards lack.
 */
@Composable
fun TerminalView(session: TerminalSession, modifier: Modifier = Modifier, fontSize: Int = 13) {
	val revision by session.changes.collectAsState()
	val measurer = rememberTextMeasurer()
	val style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = fontSize.sp)
	val cell = remember(fontSize) { measurer.measure("M", style).size }
	val background = Color(0xff101014)
	val foreground = Color(0xffe6e6e6)
	val focus = remember { FocusRequester() }
	val keyboard = LocalSoftwareKeyboardController.current
	var ctrl by remember { mutableStateOf(false) }
	var alt by remember { mutableStateOf(false) }
	var scrollOffset by remember { mutableIntStateOf(0) }

	fun send(text: String) {
		var out = text
		if (ctrl && out.length == 1) {
			val char = out[0]
			out =
				when {
					char in 'a'..'z' -> (char.code - 'a'.code + 1).toChar().toString()
					char in 'A'..'Z' -> (char.code - 'A'.code + 1).toChar().toString()
					char == ' ' || char == '@' -> "\u0000"
					char == '[' -> "\u001b"
					char == '\\' -> "\u001c"
					char == ']' -> "\u001d"
					else -> out
				}
			ctrl = false
		}
		if (alt) {
			out = "\u001b$out"
			alt = false
		}
		scrollOffset = 0
		session.type(out)
	}

	fun cursorKey(letter: Char) = send(if (session.emulator.applicationCursorKeys) "\u001bO$letter" else "\u001b[$letter")

	Column(modifier = modifier.background(background)) {
		Box(
			modifier =
				Modifier
					.weight(1f)
					.fillMaxWidth()
					.padding(4.dp)
					.onSizeChanged { size ->
						val cols = (size.width / cell.width).coerceAtLeast(10)
						val rows = (size.height / cell.height).coerceAtLeast(4)
						TerminalSize.rows = rows
						TerminalSize.cols = cols
						session.resize(rows, cols)
					}.pointerInput(Unit) {
						detectTapGestures(onTap = {
							focus.requestFocus()
							keyboard?.show()
						})
					}.semantics { contentDescription = "Terminal" }
					.onPreviewKeyEvent { event ->
						if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
						val bytes =
							terminalBytes(event.nativeKeyEvent, session.emulator.applicationCursorKeys) ?: return@onPreviewKeyEvent false
						send(bytes)
						true
					}.terminalInput(
						onText = { send(it) },
						onKey = { event -> terminalBytes(event, session.emulator.applicationCursorKeys)?.let { send(it) } != null },
					).focusRequester(focus)
					.focusable(),
		) {
			Canvas(modifier = Modifier.fillMaxSize()) {
				@Suppress("UNUSED_EXPRESSION")
				revision
				val emulator = session.emulator
				synchronized(emulator) {
					val rows = emulator.rows
					for (line in 0 until rows) {
						val sourceIndex = line - scrollOffset
						val row =
							if (sourceIndex >= 0) {
								emulator.row(sourceIndex)
							} else {
								emulator.scrollback.getOrNull(emulator.scrollback.size + sourceIndex) ?: continue
							}
						val y = line * cell.height.toFloat()
						val text = AnnotatedString.Builder()
						for (column in 0 until minOf(emulator.columns, row.chars.size)) {
							val attrs = row.attrs[column]
							var fg = colour(
								row.fg[column].let {
									if (attrs and TerminalEmulator.BOLD != 0 &&
										it in 0..7
									) {
										it + 8
									} else {
										it
									}
								},
								foreground,
							)
							var bg = colour(row.bg[column], Color.Transparent)
							if (attrs and TerminalEmulator.INVERSE != 0) {
								val swap = if (bg == Color.Transparent) background else bg
								bg = fg
								fg = swap
							}
							if (line == emulator.cursorRow + scrollOffset && column == emulator.cursorCol && emulator.cursorVisible &&
								scrollOffset == 0
							) {
								bg = foreground
								fg = background
							}
							if (bg != Color.Transparent) {
								drawRect(bg, Offset(column * cell.width.toFloat(), y), Size(cell.width.toFloat(), cell.height.toFloat()))
							}
							val start = text.length
							text.append(String(Character.toChars(row.chars[column])))
							text.addStyle(
								SpanStyle(
									color = if (attrs and TerminalEmulator.HIDDEN != 0) Color.Transparent else fg,
									fontWeight = if (attrs and TerminalEmulator.BOLD != 0) FontWeight.Bold else null,
									fontStyle = if (attrs and TerminalEmulator.ITALIC != 0) FontStyle.Italic else null,
									textDecoration =
										when {
											attrs and TerminalEmulator.UNDERLINE != 0 -> TextDecoration.Underline
											attrs and TerminalEmulator.STRIKE != 0 -> TextDecoration.LineThrough
											else -> null
										},
								),
								start,
								text.length,
							)
						}
						drawText(measurer, text.toAnnotatedString(), topLeft = Offset(0f, y), style = style, softWrap = false)
					}
				}
			}
		}
		if (session.canType) {
			Row(
				modifier = Modifier.fillMaxWidth().horizontalScroll(
					rememberScrollState(),
				).padding(horizontal = 4.dp, vertical = 2.dp),
				horizontalArrangement = Arrangement.spacedBy(4.dp),
			) {
				FilterChip(selected = false, onClick = { send("\u001b") }, label = { Text("Esc") })
				FilterChip(selected = ctrl, onClick = { ctrl = !ctrl }, label = { Text("Ctrl") })
				FilterChip(selected = alt, onClick = { alt = !alt }, label = { Text("Alt") })
				FilterChip(selected = false, onClick = { send("\t") }, label = { Text("Tab") })
				FilterChip(selected = false, onClick = { cursorKey('D') }, label = { Text("←") })
				FilterChip(selected = false, onClick = { cursorKey('A') }, label = { Text("↑") })
				FilterChip(selected = false, onClick = { cursorKey('B') }, label = { Text("↓") })
				FilterChip(selected = false, onClick = { cursorKey('C') }, label = { Text("→") })
				for (key in listOf("|", "/", "-", "~", ":")) {
					FilterChip(selected = false, onClick = { send(key) }, label = { Text(key) })
				}
				FilterChip(
					selected = scrollOffset > 0,
					onClick = {
						scrollOffset =
							if (scrollOffset > 0) 0 else minOf(session.emulator.scrollback.size, session.emulator.rows / 2)
					},
					label = { Text(if (scrollOffset > 0) "Live" else "Scroll up") },
				)
			}
		}
	}
	LaunchedEffect(session) {
		focus.requestFocus()
	}
}

@Composable
fun TerminalEnded(reason: String, onClick: () -> Unit) {
	Text(
		reason,
		style = MaterialTheme.typography.bodyMedium,
		color = MaterialTheme.colorScheme.onErrorContainer,
		modifier =
			Modifier
				.fillMaxWidth()
				.background(MaterialTheme.colorScheme.errorContainer)
				.clickable(onClick = onClick)
				.padding(12.dp),
	)
}

/** Measures the space a terminal will have, so a session can start at the right size. */
@Composable
fun TerminalMeasure(modifier: Modifier = Modifier, fontSize: Int = 13, onMeasured: () -> Unit) {
	val measurer = rememberTextMeasurer()
	val style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = fontSize.sp)
	val cell = remember(fontSize) { measurer.measure("M", style).size }
	Box(
		modifier =
			modifier.padding(4.dp).onSizeChanged { size ->
				TerminalSize.cols = (size.width / cell.width).coerceAtLeast(10)
				// The row of extra keys takes about two text rows.
				TerminalSize.rows = (size.height / cell.height - 2).coerceAtLeast(4)
				onMeasured()
			},
	)
}
