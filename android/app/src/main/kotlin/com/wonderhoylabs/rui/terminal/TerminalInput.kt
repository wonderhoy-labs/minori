package com.wonderhoylabs.rui.terminal

import android.text.InputType
import android.view.KeyEvent
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusEventModifierNode
import androidx.compose.ui.focus.FocusState
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.platform.PlatformTextInputMethodRequest
import androidx.compose.ui.platform.PlatformTextInputModifierNode
import androidx.compose.ui.platform.establishTextInputSession
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Takes the soft keyboard's input the way terminal apps do: as `TYPE_NULL`, so the keyboard sends finished text
 * and key presses instead of composing words that a terminal can't take back. Put it before `focusable()`.
 */
fun Modifier.terminalInput(
	onText: (String) -> Unit,
	onKey: (KeyEvent) -> Boolean,
): Modifier = this then TerminalInputElement(onText, onKey)

private data class TerminalInputElement(
	val onText: (String) -> Unit,
	val onKey: (KeyEvent) -> Boolean,
) : ModifierNodeElement<TerminalInputNode>() {
	override fun create() = TerminalInputNode(onText, onKey)

	override fun update(node: TerminalInputNode) {
		node.onText = onText
		node.onKey = onKey
	}

	override fun InspectorInfo.inspectableProperties() {
		name = "terminalInput"
	}
}

private class TerminalInputNode(
	var onText: (String) -> Unit,
	var onKey: (KeyEvent) -> Boolean,
) : Modifier.Node(),
	PlatformTextInputModifierNode,
	FocusEventModifierNode {
	private var session: Job? = null

	override fun onFocusEvent(focusState: FocusState) {
		if (focusState.isFocused) {
			if (session?.isActive == true) return
			session =
				coroutineScope.launch {
					establishTextInputSession {
						val request =
							PlatformTextInputMethodRequest { info ->
								info.inputType = InputType.TYPE_NULL
								info.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN
								object : BaseInputConnection(view, false) {
									override fun commitText(
										text: CharSequence?,
										newCursorPosition: Int,
									): Boolean {
										text?.toString()?.takeIf { it.isNotEmpty() }?.let { onText(it.replace('\n', '\r')) }
										return true
									}

									override fun sendKeyEvent(event: KeyEvent): Boolean {
										if (event.action == KeyEvent.ACTION_DOWN) onKey(event)
										return true
									}

									override fun deleteSurroundingText(
										beforeLength: Int,
										afterLength: Int,
									): Boolean {
										repeat(beforeLength.coerceAtMost(MAX_DELETE)) { onText("\u007f") }
										return true
									}
								}
							}
						startInputMethod(request)
					}
				}
		} else {
			session?.cancel()
			session = null
		}
	}

	companion object {
		private const val MAX_DELETE = 64
	}
}

/** What a key sends to a terminal, or null if it isn't one a terminal uses. */
fun terminalBytes(
	event: KeyEvent,
	applicationCursorKeys: Boolean,
): String? {
	val cursor = if (applicationCursorKeys) "\u001bO" else "\u001b["
	return when (event.keyCode) {
		KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> "\r"
		KeyEvent.KEYCODE_DEL -> "\u007f"
		KeyEvent.KEYCODE_FORWARD_DEL -> "\u001b[3~"
		KeyEvent.KEYCODE_TAB -> "\t"
		KeyEvent.KEYCODE_ESCAPE -> "\u001b"
		KeyEvent.KEYCODE_DPAD_UP -> "${cursor}A"
		KeyEvent.KEYCODE_DPAD_DOWN -> "${cursor}B"
		KeyEvent.KEYCODE_DPAD_RIGHT -> "${cursor}C"
		KeyEvent.KEYCODE_DPAD_LEFT -> "${cursor}D"
		KeyEvent.KEYCODE_MOVE_HOME -> "\u001b[H"
		KeyEvent.KEYCODE_MOVE_END -> "\u001b[F"
		KeyEvent.KEYCODE_PAGE_UP -> "\u001b[5~"
		KeyEvent.KEYCODE_PAGE_DOWN -> "\u001b[6~"
		else -> {
			val char = event.getUnicodeChar(event.metaState and KeyEvent.META_CTRL_MASK.inv())
			when {
				char == 0 -> null
				event.isCtrlPressed && char.toChar().lowercaseChar() in 'a'..'z' -> (char.toChar().lowercaseChar() - 'a' + 1).toChar().toString()
				event.isAltPressed -> "\u001b${char.toChar()}"
				else -> String(Character.toChars(char))
			}
		}
	}
}
