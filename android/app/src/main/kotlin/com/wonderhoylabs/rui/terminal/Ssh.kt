package com.wonderhoylabs.rui.terminal

import com.jcraft.jsch.ChannelShell
import com.jcraft.jsch.JSch
import com.jcraft.jsch.KeyPair
import com.jcraft.jsch.Proxy
import com.jcraft.jsch.SocketFactory
import com.jcraft.jsch.UIKeyboardInteractive
import com.jcraft.jsch.UserInfo
import com.wonderhoylabs.rui.backend.GuestConnection
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket

/** An SSH shell's streams, for a terminal or a backend's console. */
class ShellChannel(
	val output: InputStream,
	val input: OutputStream,
	val resize: (rows: Int, cols: Int) -> Unit,
	val close: () -> Unit,
)

/** An SSH key pair, as OpenSSH writes them. */
data class SshKey(
	val privateKey: String,
	val publicKey: String,
)

object Ssh {
	private const val CONNECT_TIMEOUT_MS = 20_000

	/** Makes an RSA key for logging in to a new VM. RSA works on every Android version without extra providers. */
	fun generateKey(comment: String): SshKey {
		val pair = KeyPair.genKeyPair(JSch(), KeyPair.RSA, 3072)
		val private = ByteArrayOutputStream().also { pair.writePrivateKey(it) }.toString(Charsets.UTF_8)
		val public = ByteArrayOutputStream().also { pair.writePublicKey(it, comment) }.toString(Charsets.UTF_8).trim()
		pair.dispose()
		return SshKey(private, public)
	}

	/**
	 * Logs in over SSH through [open], a fresh connection to the guest's SSH port (through rui-agent, or a
	 * published port), and starts a shell. [confirmHostKey] is asked, on this thread, whenever the guest's host key
	 * is new or has changed; it should ask the user and return their answer. Blocks; call on a background thread.
	 */
	fun channel(
		hostAlias: String,
		user: String,
		password: String?,
		privateKey: String?,
		knownHosts: File,
		open: () -> GuestConnection,
		confirmHostKey: (message: String) -> Boolean,
		rows: Int = 24,
		cols: Int = 80,
	): ShellChannel {
		knownHosts.parentFile?.mkdirs()
		if (!knownHosts.exists()) knownHosts.createNewFile()
		val jsch = JSch()
		jsch.setKnownHosts(knownHosts.path)
		privateKey?.let { jsch.addIdentity("rui", it.toByteArray(), null, null) }
		val session = jsch.getSession(user, hostAlias, 22)
		session.setProxy(GuestProxy(open))
		password?.let { session.setPassword(it) }
		session.setConfig("StrictHostKeyChecking", "ask")
		session.setConfig("PreferredAuthentications", "publickey,keyboard-interactive,password")
		session.userInfo = Prompts(password, confirmHostKey)
		session.connect(CONNECT_TIMEOUT_MS)
		val channel = session.openChannel("shell") as ChannelShell
		channel.setPtyType("xterm-256color")
		channel.setPtySize(cols, rows, 0, 0)
		val output = channel.inputStream
		val input = channel.outputStream
		channel.connect(CONNECT_TIMEOUT_MS)
		return ShellChannel(output, input, { r, c -> channel.setPtySize(c, r, 0, 0) }) {
			channel.disconnect()
			session.disconnect()
		}
	}

	/** [channel] as a terminal. */
	fun shell(
		title: String,
		hostAlias: String,
		user: String,
		password: String?,
		privateKey: String?,
		knownHosts: File,
		open: () -> GuestConnection,
		confirmHostKey: (message: String) -> Boolean,
	): TerminalSession {
		val shell = channel(hostAlias, user, password, privateKey, knownHosts, open, confirmHostKey)
		return TerminalSession(title, shell.output, shell.input, onResize = shell.resize, onClose = shell.close)
	}

	private class Prompts(
		private val password: String?,
		private val confirm: (String) -> Boolean,
	) : UserInfo,
		UIKeyboardInteractive {
		override fun getPassphrase(): String? = null

		override fun getPassword(): String? = password

		override fun promptPassword(message: String?): Boolean = password != null

		override fun promptPassphrase(message: String?): Boolean = false

		override fun promptYesNo(message: String): Boolean = confirm(message)

		override fun showMessage(message: String?) {}

		override fun promptKeyboardInteractive(
			destination: String?,
			name: String?,
			instruction: String?,
			prompt: Array<out String>,
			echo: BooleanArray,
		): Array<String>? = if (password == null) null else Array(prompt.size) { password }
	}

	/** Hands JSch a connection Rui already made, instead of letting it open a socket. */
	private class GuestProxy(
		private val open: () -> GuestConnection,
	) : Proxy {
		private var connection: GuestConnection? = null

		override fun connect(
			socketFactory: SocketFactory?,
			host: String?,
			port: Int,
			timeout: Int,
		) {
			connection = open()
		}

		override fun getInputStream(): InputStream = checkNotNull(connection).input

		override fun getOutputStream(): OutputStream = checkNotNull(connection).output

		override fun getSocket(): Socket? = null

		override fun close() {
			runCatching { connection?.close() }
		}
	}
}
