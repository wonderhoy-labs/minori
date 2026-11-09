package com.wonderhoylabs.minori.setup

import kotlin.system.exitProcess

/**
 * Runs in a process Shizuku starts as the shell user, which may grant development permissions. Minori only uses it
 * to grant its own permissions, so a user with Shizuku never has to type an adb command.
 */
class ShellService : IShell.Stub() {
	override fun run(script: String): String {
		val process = ProcessBuilder("sh", "-c", script).redirectErrorStream(true).start()
		process.outputStream.close()
		val output = process.inputStream.bufferedReader().use { it.readText() }
		val status = process.waitFor()
		return "$output\n$status"
	}

	override fun destroy() {
		exitProcess(0)
	}
}
