package com.wonderhoylabs.minori.vnc

import android.graphics.Bitmap
import com.wonderhoylabs.minori.backend.GuestConnection
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * A small VNC (RFB 3.8) client: no password or a VNC password, raw and copy-rect updates, 32-bit colour, the
 * pointer and the keyboard. Enough for a guest desktop over Minori's own connection, where bandwidth is free.
 */
class RfbClient(private val connection: GuestConnection, private val password: String?) {
	private val input = DataInputStream(connection.input.buffered(64 * 1024))
	private val output = DataOutputStream(BufferedOutputStream(connection.output))

	var width = 0
		private set
	var height = 0
		private set
	var name = ""
		private set

	@Volatile var bitmap: Bitmap? = null
		private set
	private var pixels = IntArray(0)

	private val frameCount = MutableStateFlow(0L)

	/** Bumps whenever [bitmap] has new pixels. */
	val frames: StateFlow<Long> = frameCount.asStateFlow()

	/** Does the RFB handshake. Throws with a message for the user if the server wants something Minori can't do. */
	fun handshake() {
		val version = ByteArray(12).also { input.readFully(it) }.toString(Charsets.US_ASCII)
		if (!version.startsWith("RFB ")) throw IOException("That port isn't a VNC server")
		output.write("RFB 003.008\n".toByteArray())
		output.flush()
		val count = input.readUnsignedByte()
		if (count == 0) throw IOException(readReason())
		val types = ByteArray(count).also { input.readFully(it) }.map { it.toInt() and 0xff }
		when {
			NONE in types -> {
				output.writeByte(NONE)
				output.flush()
			}

			VNC_AUTH in types -> {
				output.writeByte(VNC_AUTH)
				output.flush()
				val challenge = ByteArray(16).also { input.readFully(it) }
				output.write(encryptChallenge(challenge, password ?: throw IOException("This VNC server needs a password")))
				output.flush()
			}

			else -> throw IOException("This VNC server only offers security Minori doesn't support. Allow plain VNC passwords.")
		}
		if (input.readInt() != 0) throw IOException(runCatching { readReason() }.getOrDefault("The VNC password was refused"))
		output.writeByte(1)
		output.flush()
		width = input.readUnsignedShort()
		height = input.readUnsignedShort()
		discard(16)
		name = ByteArray(input.readInt()).also { input.readFully(it) }.toString(Charsets.UTF_8)
		resizeFramebuffer(width, height)
		// 32 bits per pixel, true colour, little-endian BGRX, which is Android's ARGB once read as an int.
		output.writeByte(0)
		output.write(ByteArray(3))
		output.writeByte(32)
		output.writeByte(24)
		output.writeByte(0)
		output.writeByte(1)
		output.writeShort(255)
		output.writeShort(255)
		output.writeShort(255)
		output.writeByte(16)
		output.writeByte(8)
		output.writeByte(0)
		output.write(ByteArray(3))
		val encodings = intArrayOf(RAW, COPY_RECT, DESKTOP_SIZE)
		output.writeByte(2)
		output.writeByte(0)
		output.writeShort(encodings.size)
		encodings.forEach { output.writeInt(it) }
		requestUpdate(incremental = false)
	}

	// Reads and drops bytes. DataInputStream.skipBytes can't be used: past the buffer it seeks, which a vsock refuses
	// with EOPNOTSUPP, and it may skip fewer bytes than asked.
	private fun discard(count: Int) {
		val scratch = ByteArray(minOf(count.coerceAtLeast(0), 8192))
		var left = count
		while (left > 0) {
			val read = input.read(scratch, 0, minOf(left, scratch.size))
			if (read < 0) throw java.io.EOFException("The VNC server closed the connection")
			left -= read
		}
	}

	private fun readReason(): String = ByteArray(input.readInt().coerceIn(0, 4096)).also {
		input.readFully(it)
	}.toString(Charsets.UTF_8)

	private fun resizeFramebuffer(w: Int, h: Int) {
		width = w
		height = h
		pixels = IntArray(w * h)
		bitmap = Bitmap.createBitmap(w.coerceAtLeast(1), h.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
	}

	@Synchronized
	private fun requestUpdate(incremental: Boolean) {
		output.writeByte(3)
		output.writeByte(if (incremental) 1 else 0)
		output.writeShort(0)
		output.writeShort(0)
		output.writeShort(width)
		output.writeShort(height)
		output.flush()
	}

	/** Reads updates until the connection ends. Call on its own thread. */
	fun run() {
		val row = ByteArray(width.coerceAtLeast(1) * 4)
		var rowBuffer = row
		while (true) {
			when (val type = input.readUnsignedByte()) {
				0 -> {
					input.readUnsignedByte()
					val rects = input.readUnsignedShort()
					repeat(rects) {
						val x = input.readUnsignedShort()
						val y = input.readUnsignedShort()
						val w = input.readUnsignedShort()
						val h = input.readUnsignedShort()
						when (val encoding = input.readInt()) {
							RAW -> {
								if (rowBuffer.size < w * 4) rowBuffer = ByteArray(w * 4)
								for (line in 0 until h) {
									input.readFully(rowBuffer, 0, w * 4)
									val base = (y + line) * width + x
									for (column in 0 until w) {
										val at = column * 4
										val b = rowBuffer[at].toInt() and 0xff
										val g = rowBuffer[at + 1].toInt() and 0xff
										val r = rowBuffer[at + 2].toInt() and 0xff
										if (base + column < pixels.size) pixels[base + column] = (0xff shl 24) or (r shl 16) or (g shl 8) or b
									}
								}
								publish(x, y, w, h)
							}

							COPY_RECT -> {
								val sx = input.readUnsignedShort()
								val sy = input.readUnsignedShort()
								val copy = IntArray(w * h)
								for (line in 0 until h) System.arraycopy(pixels, (sy + line) * width + sx, copy, line * w, w)
								for (line in 0 until h) System.arraycopy(copy, line * w, pixels, (y + line) * width + x, w)
								publish(x, y, w, h)
							}

							DESKTOP_SIZE -> {
								synchronized(this) { resizeFramebuffer(w, h) }
								rowBuffer = ByteArray(w * 4)
							}

							else -> throw IOException("The VNC server sent encoding $encoding, which Minori didn't ask for")
						}
					}
					requestUpdate(incremental = true)
				}

				1 -> {
					input.readUnsignedByte()
					input.readUnsignedShort()
					val colours = input.readUnsignedShort()
					discard(colours * 6)
				}

				2 -> {}

				3 -> {
					discard(3)
					discard(input.readInt())
				}

				else -> throw IOException("The VNC server sent message $type, which Minori doesn't understand")
			}
		}
	}

	private fun publish(x: Int, y: Int, w: Int, h: Int) {
		val target = bitmap ?: return
		if (w <= 0 || h <= 0 || x + w > width || y + h > height) return
		target.setPixels(pixels, y * width + x, width, x, y, w, h)
		frameCount.value++
	}

	@Synchronized
	fun pointer(x: Int, y: Int, buttons: Int) {
		runCatching {
			output.writeByte(5)
			output.writeByte(buttons)
			output.writeShort(x.coerceIn(0, width - 1))
			output.writeShort(y.coerceIn(0, height - 1))
			output.flush()
		}
	}

	@Synchronized
	fun key(keysym: Int, down: Boolean) {
		runCatching {
			output.writeByte(4)
			output.writeByte(if (down) 1 else 0)
			output.writeShort(0)
			output.writeInt(keysym)
			output.flush()
		}
	}

	fun type(text: String) {
		text.codePoints().forEach { code ->
			val keysym =
				when {
					code == '\n'.code || code == '\r'.code -> KEY_RETURN
					code < 0x100 -> code
					else -> 0x01000000 or code
				}
			key(keysym, true)
			key(keysym, false)
		}
	}

	fun close() = connection.close()

	companion object {
		const val KEY_RETURN = 0xff0d
		const val KEY_BACKSPACE = 0xff08
		const val KEY_TAB = 0xff09
		const val KEY_ESCAPE = 0xff1b
		const val KEY_LEFT = 0xff51
		const val KEY_UP = 0xff52
		const val KEY_RIGHT = 0xff53
		const val KEY_DOWN = 0xff54
		private const val NONE = 1
		private const val VNC_AUTH = 2
		private const val RAW = 0
		private const val COPY_RECT = 1
		private const val DESKTOP_SIZE = -223

		/** VNC authentication: DES with the password as the key, each byte's bits reversed, as the RFB spec says. */
		fun encryptChallenge(challenge: ByteArray, password: String): ByteArray {
			val key = ByteArray(8)
			password.toByteArray(Charsets.ISO_8859_1).copyInto(key, 0, 0, minOf(8, password.length))
			for (index in key.indices) key[index] = (Integer.reverse(key[index].toInt() and 0xff) ushr 24).toByte()
			@Suppress("GetInstance") // DES/ECB is what the RFB protocol defines.
			val cipher = Cipher.getInstance("DES/ECB/NoPadding")
			cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "DES"))
			return cipher.doFinal(challenge)
		}
	}
}
