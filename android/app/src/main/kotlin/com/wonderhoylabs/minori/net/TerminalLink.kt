package com.wonderhoylabs.minori.net

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.provider.MediaStore
import com.wonderhoylabs.minori.backend.Agent
import com.wonderhoylabs.minori.backend.DemoHypervisor
import com.wonderhoylabs.minori.backend.GuestConnection
import com.wonderhoylabs.minori.service.MinoriService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.IOException
import java.security.SecureRandom

/** Whether Android's terminal has Minori's agent, as far as Minori can tell from outside. */
enum class TerminalAgent {
	/** The agent answered with this phone's token. */
	Ready,

	/** Something answers on the port but doesn't know this phone's token: an install from another copy of Minori. */
	WrongToken,

	/** Nothing answers: Linux isn't running, the agent isn't installed, or Android hasn't been allowed the port. */
	Unreachable,
}

/**
 * Carries the traffic of Android's own Linux terminal (`com.android.virtualization.terminal`) through Minori.
 *
 * Android gives the terminal's VM its network by tethering it to the phone's current network, and tethered traffic
 * never goes through an app's VPN. So with Saki, Akiyama or Shira as the phone's VPN, Minori's machines reach the
 * internet through it, but the terminal doesn't, and with Wi-Fi and mobile data off it has none at all.
 *
 * Here the terminal's Linux runs the same `minori-agent` as Minori's machines, on TCP 127.0.0.1:5741, installed by
 * `terminal-install.sh`. The terminal forwards that port to the phone's loopback, and Minori asks it for `NET` just as
 * it does over vsock for its own machines: the agent puts Linux's packets on a TUN device with the default route, and
 * [GuestNetwork] turns them into Minori's own sockets, which follow the phone's VPN. Any app can reach a port the
 * terminal forwards, so every connection starts with a token only this copy of Minori and the agent know.
 */
class TerminalLink(private val context: Context) {
	private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
	private val networkFlow = MutableStateFlow<GuestNetwork?>(null)

	/** The link while it's on, for its state. */
	val network: StateFlow<GuestNetwork?> = networkFlow.asStateFlow()

	val enabled: Boolean get() = prefs.getBoolean(ENABLED, false)

	private val token: String
		get() {
			prefs.getString(TOKEN, null)?.let { return it }
			val token = ByteArray(TOKEN_BYTES).also(SecureRandom()::nextBytes).joinToString("") { "%02x".format(it) }
			prefs.edit().putString(TOKEN, token).apply()
			return token
		}

	private val guest =
		object : AgentGuest {
			override fun agentRequest(request: String): GuestConnection {
				val connection = DemoHypervisor.tcp(ADDRESS)
				try {
					connection.output.write("TOKEN $token\n".toByteArray())
					Agent.request(connection, request)
				} catch (error: IOException) {
					connection.close()
					throw error
				}
				return connection
			}
		}

	/** Turns the link on or off, and remembers the choice. */
	@Synchronized
	fun setEnabled(on: Boolean) {
		prefs.edit().putBoolean(ENABLED, on).apply()
		networkFlow.value?.stop()
		networkFlow.value = if (on) GuestNetwork(NAME, guest) else null
		MinoriService.update(context)
	}

	/** Starts the link if it was on when Minori last ran. */
	@Synchronized
	fun resume() {
		if (enabled && networkFlow.value == null) {
			networkFlow.value = GuestNetwork(NAME, guest)
			MinoriService.update(context)
		}
	}

	/** Asks the agent who it is. Blocks; call on a background thread. */
	fun check(): TerminalAgent = try {
		guest.agentRequest("HELLO").close()
		TerminalAgent.Ready
	} catch (error: IOException) {
		if (error.message == "wrong token") TerminalAgent.WrongToken else TerminalAgent.Unreachable
	}

	/**
	 * Puts the agent and its installer where the terminal's Linux sees the phone's Downloads, and returns the command
	 * that installs them. Android has no way for an app to run something in the terminal, so this is the one step
	 * someone does by hand.
	 *
	 * The token travels in the command, not a file, because every app with storage access can read Downloads. The
	 * folder's name is new each time, because Android won't let an app replace files an earlier install of it left.
	 */
	fun prepareInstall(): String {
		val folder =
			"Minori-" + ByteArray(FOLDER_BYTES).also(SecureRandom()::nextBytes).joinToString("") { "%02x".format(it) }
		for ((name, asset) in listOf("minori-agent" to AGENT_ASSET, "install.sh" to INSTALLER_ASSET)) {
			val values =
				ContentValues().apply {
					put(MediaStore.Downloads.DISPLAY_NAME, name)
					put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
					put(MediaStore.Downloads.RELATIVE_PATH, "Download/$folder")
				}
			val resolver = context.contentResolver
			val uri =
				resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
					?: throw IOException("Android wouldn't create $name in Downloads")
			val output = resolver.openOutputStream(uri) ?: throw IOException("Android wouldn't open $name in Downloads")
			output.use { out -> context.assets.open(asset).use { it.copyTo(out) } }
		}
		// The space at the start keeps the token out of the shell's history.
		return " sh /mnt/shared/Download/$folder/install.sh $token"
	}

	fun terminalIntent(): Intent? = context.packageManager.getLaunchIntentForPackage(TERMINAL_PACKAGE)

	companion object {
		const val TERMINAL_PACKAGE = "com.android.virtualization.terminal"

		/** The port the agent listens on in the terminal's Linux, and where the terminal forwards it on the phone. */
		const val PORT = 5741
		private const val ADDRESS = "127.0.0.1:$PORT"
		private const val NAME = "Terminal"
		private const val PREFS = "terminal"
		private const val ENABLED = "enabled"
		private const val TOKEN = "token"
		private const val TOKEN_BYTES = 32
		private const val FOLDER_BYTES = 3
		private const val AGENT_ASSET = "minori-agent-aarch64"
		private const val INSTALLER_ASSET = "terminal-install.sh"
	}
}
