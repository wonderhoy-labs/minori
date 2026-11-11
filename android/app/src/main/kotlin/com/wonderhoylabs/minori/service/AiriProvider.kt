package com.wonderhoylabs.minori.service

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Binder
import android.os.ParcelFileDescriptor
import android.util.Log
import com.wonderhoylabs.minori.MinoriApp
import com.wonderhoylabs.minori.model.VmConfig
import com.wonderhoylabs.minori.model.VmState
import com.wonderhoylabs.minori.net.PortForwarder
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import kotlin.concurrent.thread

/**
 * How Airi reaches Linux apps in Minori's machines, alongside Android's own terminal. docs/airi.md is the contract.
 *
 * - `content://com.wonderhoylabs.minori.airi/machines` lists the machines Airi's agent can run in: `id`, `state`, `airi`
 *   (1 once Airi is set up in it) and `name`, in that order, so the name comes last.
 * - Opening `content://com.wonderhoylabs.minori.airi/machines/<id>/agent` for reading and writing gives a socket that's
 *   connected to Airi's agent in that running machine, on the guest's port 47821. Airi speaks its own protocol over it,
 *   with its own handshake, so the agent still only answers the Airi that holds its token.
 *
 * Only Airi, and adb's shell for tests, may use it. Android tells Minori who's asking.
 */
class AiriProvider : ContentProvider() {
	override fun onCreate(): Boolean = true

	private val app: MinoriApp get() = MinoriApp.get(context!!).also(MinoriApp::awaitReady)

	private fun checkCaller() {
		val uid = Binder.getCallingUid()
		if (uid == ROOT_UID || uid == SHELL_UID || callingPackage == AIRI_PACKAGE) return
		throw SecurityException("Only Airi can reach Minori's machines this way")
	}

	override fun query(
		uri: Uri,
		projection: Array<out String>?,
		selection: String?,
		selectionArgs: Array<out String>?,
		sortOrder: String?,
	): Cursor {
		checkCaller()
		if (uri.pathSegments != listOf(MACHINES)) throw IllegalArgumentException("Minori only lists $MACHINES, not $uri")
		val cursor = MatrixCursor(COLUMNS)
		rows(app.vms.vms.value) { app.machines.state(it) }.forEach(cursor::addRow)
		return cursor
	}

	override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
		checkCaller()
		val id = agentMachine(uri) ?: throw FileNotFoundException("No such file: $uri")
		if (mode != "rw") throw FileNotFoundException("Open Airi's agent for reading and writing")
		val vm = app.vms.get(id) ?: throw FileNotFoundException("There's no machine $id")
		if (!AiriSetup.isSetUp(vm)) throw FileNotFoundException("Airi isn't set up in ${vm.name}")
		val guest = app.machines.guest(id) ?: throw FileNotFoundException("${vm.name} isn't running")
		val remote =
			try {
				guest.connect(AiriSetup.agentPort(vm))
			} catch (error: IOException) {
				throw FileNotFoundException("Airi's agent isn't answering in ${vm.name}: ${error.message}")
			}
		val (ours, theirs) = ParcelFileDescriptor.createSocketPair()
		thread(name = "airi-${vm.name}", isDaemon = true) {
			try {
				PortForwarder.splice(FileInputStream(ours.fileDescriptor), FileOutputStream(ours.fileDescriptor), remote) {}
			} catch (error: Exception) {
				Log.w(TAG, "Airi's connection to ${vm.name} failed", error)
			} finally {
				runCatching { ours.close() }
			}
		}
		return theirs
	}

	override fun getType(uri: Uri): String? = when {
		uri.pathSegments == listOf(MACHINES) -> "vnd.android.cursor.dir/vnd.com.wonderhoylabs.minori.machine"
		agentMachine(uri) != null -> "application/octet-stream"
		else -> null
	}

	override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException("Read only")

	override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
		throw UnsupportedOperationException("Read only")

	override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int =
		throw UnsupportedOperationException("Read only")

	companion object {
		/** The provider's authority. */
		const val AUTHORITY = "com.wonderhoylabs.minori.airi"
		const val AIRI_PACKAGE = "com.wonderhoylabs.airi"
		private const val MACHINES = "machines"
		private const val AGENT = "agent"
		val COLUMNS = arrayOf("id", "state", "airi", "name")
		private const val TAG = "MinoriAiri"
		private const val ROOT_UID = 0
		private const val SHELL_UID = 2000

		/** The machine in `machines/<id>/agent`, or null if [uri] isn't one. */
		fun agentMachine(uri: Uri): String? = agentMachine(uri.pathSegments)

		fun agentMachine(segments: List<String>): String? =
			segments.takeIf { it.size == 3 && it[0] == MACHINES && it[2] == AGENT && it[1].isNotEmpty() }?.get(1)

		/** One row per machine Airi's agent can run in, in [COLUMNS]' order. */
		fun rows(vms: List<VmConfig>, state: (String) -> VmState): List<Array<Any>> = vms
			.filter(AiriSetup::canSetUp)
			.map { vm -> arrayOf(vm.id, stateName(state(vm.id)), if (AiriSetup.isSetUp(vm)) 1 else 0, vm.name) }

		/** The same names as the helper's `list`. */
		fun stateName(state: VmState): String = when (state) {
			VmState.Stopped -> "stopped"
			VmState.Starting -> "starting"
			VmState.Running -> "running"
			VmState.Stopping -> "stopping"
			is VmState.Failed -> "error"
		}
	}
}
