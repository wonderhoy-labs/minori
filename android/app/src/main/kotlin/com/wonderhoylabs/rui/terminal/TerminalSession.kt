package com.wonderhoylabs.rui.terminal

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import android.util.Log
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.Executors
import kotlin.concurrent.thread

/** The size the terminal view last measured, so a new session starts at it instead of 80 by 24. */
object TerminalSize {
	@Volatile var rows = 24

	@Volatile var cols = 80
}

/**
 * A terminal attached to something: a VM's console, an SSH shell, a local shell. It reads output into its
 * [emulator] on its own thread, and [write] sends keys.
 */
open class TerminalSession(
	val title: String,
	private val output: InputStream,
	private val input: OutputStream?,
	private val onResize: (rows: Int, cols: Int) -> Unit = { _, _ -> },
	private val onClose: () -> Unit = {},
) {
	private val revisions = MutableStateFlow(0L)

	/** Bumps on every screen change, for a view to collect. */
	val changes: StateFlow<Long> = revisions.asStateFlow()

	private val ended = MutableStateFlow<String?>(null)

	/** Null while attached; a reason once the other end has gone. */
	val endReason: StateFlow<String?> = ended.asStateFlow()

	val emulator = TerminalEmulator(TerminalSize.cols, TerminalSize.rows, respond = { write(it) })

	// Keys and resizes go out one at a time, in order, and never on the main thread: SSH writes to the network.
	private val sender = Executors.newSingleThreadExecutor { Thread(it, "terminal-send").apply { isDaemon = true } }

	val canType: Boolean get() = input != null

	private val reader =
		thread(name = "terminal-$title", isDaemon = true) {
			val buffer = ByteArray(16 * 1024)
			try {
				while (true) {
					val n = output.read(buffer)
					if (n < 0) break
					emulator.feed(buffer, 0, n)
					revisions.value = emulator.revision
				}
				ended.value = "Disconnected"
			} catch (error: Exception) {
				Log.i("RuiTerminal", "$title ended", error)
				ended.value = error.message ?: "Disconnected"
			}
			revisions.value = emulator.revision + 1
		}

	fun write(bytes: ByteArray) {
		val target = input ?: return
		runCatching {
			sender.execute {
				runCatching {
					target.write(bytes)
					target.flush()
				}
			}
		}
	}

	fun type(text: String) = write(text.toByteArray())

	/** Pastes text, wrapped for bracketed paste when the program asked for it, so it isn't run line by line. */
	fun paste(text: String) {
		val normalised = text.replace("\r\n", "\r").replace('\n', '\r')
		if (emulator.bracketedPaste) type("\u001b[200~$normalised\u001b[201~") else type(normalised)
	}

	fun resize(
		rows: Int,
		cols: Int,
	) {
		if (rows == emulator.rows && cols == emulator.columns) return
		emulator.resize(cols, rows)
		revisions.value = emulator.revision
		runCatching { sender.execute { runCatching { onResize(rows, cols) } } }
	}

	open fun close() {
		sender.shutdown()
		runCatching { onClose() }
		runCatching { input?.close() }
		runCatching { output.close() }
	}
}
