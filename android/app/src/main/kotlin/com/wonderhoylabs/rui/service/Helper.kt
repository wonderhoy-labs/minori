package com.wonderhoylabs.rui.service

import android.net.LocalServerSocket
import android.net.LocalSocket
import android.util.Log
import com.wonderhoylabs.rui.BuildConfig
import com.wonderhoylabs.rui.RuiApp
import com.wonderhoylabs.rui.core.RuiJson
import com.wonderhoylabs.rui.model.VmConfig
import com.wonderhoylabs.rui.model.VmState
import com.wonderhoylabs.rui.net.PortForwarder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import kotlin.concurrent.thread

/**
 * Rui's contract for pages and tools that reach the phone over adb, such as Asahi: newline-delimited JSON on the
 * abstract Unix socket `rui`, which adb forwards (`adb forward tcp:5742 localabstract:rui`, or Tango's
 * `createSocket("localabstract:rui")` in a page). Only adb's shell user and root may connect; Android tells Rui each
 * peer's uid, and every other app is refused. The protocol is in docs/asahi-integration.md.
 */
class Helper(private val app: RuiApp) {
	fun start() {
		thread(name = "rui-helper", isDaemon = true) {
			val server =
				try {
					LocalServerSocket(SOCKET)
				} catch (error: IOException) {
					Log.w(TAG, "Couldn't open the helper socket", error)
					return@thread
				}
			while (true) {
				val socket = runCatching { server.accept() }.getOrNull() ?: break
				thread(name = "rui-helper-client", isDaemon = true) { serve(socket) }
			}
		}
	}

	private fun serve(socket: LocalSocket) {
		socket.use {
			val input = socket.inputStream
			val output = socket.outputStream
			val uid = runCatching { socket.peerCredentials.uid }.getOrDefault(-1)
			if (uid != ROOT_UID && uid != SHELL_UID) {
				send(output, error(null, "Only adb can use Rui's helper"))
				return
			}
			while (true) {
				val line = readLine(input) ?: return
				val request = runCatching { RuiJson.parseToJsonElement(line).jsonObject }.getOrNull()
				if (request == null) {
					send(output, error(null, "That isn't a JSON object"))
					continue
				}
				val id = request["id"]
				val method = request["method"]?.jsonPrimitive?.content
				val params = request["params"] as? JsonObject ?: JsonObject(emptyMap())
				try {
					when (method) {
						"hello" -> send(output, result(id, hello()))

						"list" -> send(output, result(id, buildJsonArray { app.vms.vms.value.forEach { add(summary(it)) } }))

						"start" -> {
							val vm = vm(params)
							app.machines.start(vm.id)
							send(output, result(id, summary(vm)))
						}

						"stop" -> {
							val vm = vm(params)
							app.machines.stop(vm.id)
							send(output, result(id, summary(vm)))
						}

						"ports" -> send(output, result(id, ports(vm(params))))

						"connect" -> {
							val vm = vm(params)
							val port = params["port"]?.jsonPrimitive?.intOrNull ?: throw IOException("connect needs a port")
							val guest = app.machines.guest(vm.id) ?: throw IOException("${vm.name} isn't running")
							val remote = guest.connect(port)
							send(output, result(id, buildJsonObject { put("stream", true) }))
							PortForwarder.splice(input, output, remote) {}
							return
						}

						"console" -> {
							val vm = vm(params)
							val session = app.machines.console(vm.id) ?: throw IOException("${vm.name} isn't running")
							send(output, result(id, buildJsonObject { put("stream", true) }))
							val tap = session.tap { bytes -> runCatching { synchronized(output) { output.write(bytes) } } }
							try {
								val buffer = ByteArray(4096)
								while (true) {
									val n = input.read(buffer)
									if (n < 0) break
									session.write(buffer.copyOf(n))
								}
							} finally {
								tap.close()
							}
							return
						}

						else -> send(output, error(id, "Unknown method ${method ?: "(none)"}"))
					}
				} catch (failure: IOException) {
					send(output, error(id, failure.message ?: "Failed"))
				}
			}
		}
	}

	private fun hello(): JsonObject = buildJsonObject {
		put("name", "Rui")
		put("version", BuildConfig.VERSION_NAME)
		put("protocol", PROTOCOL)
		put("methods", buildJsonArray { METHODS.forEach { add(JsonPrimitive(it)) } })
	}

	private fun vm(params: JsonObject): VmConfig {
		val id = params["vm"]?.jsonPrimitive?.content ?: throw IOException("Say which machine with \"vm\"")
		return app.vms.get(id) ?: app.vms.vms.value.firstOrNull { it.name == id }
			?: throw IOException("There's no machine $id")
	}

	private fun summary(vm: VmConfig): JsonObject = buildJsonObject {
		put("id", vm.id)
		put("name", vm.name)
		put("backend", vm.backend.name.lowercase())
		put("state", stateName(app.machines.state(vm.id)))
		vm.os?.let { put("os", it) }
		put("agent", vm.agent)
		put("vncPort", vm.access.vncPort)
		put("sshPort", vm.access.sshPort)
		put("rdpPort", vm.access.rdpPort)
		put("user", vm.access.user)
	}

	private fun ports(vm: VmConfig): JsonObject {
		val status = app.machines.forwarder.status.value
		return buildJsonObject {
			put(
				"published",
				buildJsonArray {
					vm.network.forwards.forEach { forward ->
						add(
							buildJsonObject {
								put("hostPort", forward.hostPort)
								put("guestPort", forward.guestPort)
								put("protocol", forward.protocol.name.lowercase())
								put("exposure", forward.exposure.name.lowercase())
								put("listening", status[forward.id]?.listening == true)
							},
						)
					}
				},
			)
			val listening = app.machines.guest(vm.id)?.listeningPorts()
			if (listening != null) put("listening", buildJsonArray { listening.forEach { add(JsonPrimitive(it)) } })
		}
	}

	private fun stateName(state: VmState): String = when (state) {
		VmState.Stopped -> "stopped"
		VmState.Starting -> "starting"
		VmState.Running -> "running"
		VmState.Stopping -> "stopping"
		is VmState.Failed -> "error"
	}

	private fun result(id: JsonElement?, value: JsonElement) = buildJsonObject {
		id?.let { put("id", it) }
		put("result", value)
	}

	private fun error(id: JsonElement?, message: String) = buildJsonObject {
		id?.let { put("id", it) }
		put("error", message)
	}

	private fun send(output: OutputStream, message: JsonObject) {
		synchronized(output) {
			output.write("$message\n".toByteArray())
			output.flush()
		}
	}

	private fun readLine(input: InputStream): String? {
		val line = ByteArrayOutputStream()
		while (line.size() < MAX_LINE) {
			val byte = input.read()
			if (byte < 0) return null
			if (byte == '\n'.code) return String(line.toByteArray(), Charsets.UTF_8)
			line.write(byte)
		}
		return null
	}

	companion object {
		const val SOCKET = "rui"
		const val PROTOCOL = 1
		val METHODS = listOf("hello", "list", "start", "stop", "ports", "connect", "console")
		private const val TAG = "RuiHelper"
		private const val ROOT_UID = 0
		private const val SHELL_UID = 2000
		private const val MAX_LINE = 64 * 1024
	}
}
