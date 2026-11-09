package com.wonderhoylabs.rui.store

import android.os.StatFs
import com.wonderhoylabs.rui.core.Native
import com.wonderhoylabs.rui.core.RuiJson
import com.wonderhoylabs.rui.model.ImageRecord
import com.wonderhoylabs.rui.model.VmConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.IOException

/** Writes a file so a crash halfway leaves the old one, never a torn one. */
fun File.writeAtomically(text: String) {
	val temporary = File(parentFile, "$name.part")
	temporary.writeText(text)
	if (!temporary.renameTo(this)) {
		temporary.delete()
		throw IOException("Couldn't save $name")
	}
}

/** How much a file really takes on disk and how big it says it is. */
data class DiskUsage(
	val used: Long,
	val allocated: Long,
)

fun File.usage(): DiskUsage {
	if (!exists()) return DiskUsage(0, 0)
	val used = runCatching { Native.allocatedBytes(path) }.getOrDefault(-1L).takeIf { it >= 0 } ?: length()
	return DiskUsage(used = used, allocated = length())
}

fun freeBytes(dir: File): Long =
	runCatching {
		dir.mkdirs()
		StatFs(dir.path).availableBytes
	}.getOrDefault(0L)

/**
 * The machines, one folder each under `vms/`: `config.json`, the disks, `boot/` with the extracted kernel, and
 * `logs/`. The list is a [StateFlow] so every screen follows changes.
 */
class VmStore(
	private val root: File,
) {
	private val state = MutableStateFlow<List<VmConfig>>(emptyList())
	val vms: StateFlow<List<VmConfig>> = state.asStateFlow()

	init {
		root.mkdirs()
		reload()
	}

	fun reload() {
		val loaded =
			root.listFiles().orEmpty().filter { it.isDirectory }.mapNotNull { dir ->
				runCatching { RuiJson.decodeFromString<VmConfig>(File(dir, CONFIG).readText()) }.getOrNull()
			}
		state.value = loaded.sortedBy { it.createdAt }
	}

	fun dir(id: String): File = File(root, id)

	fun get(id: String): VmConfig? = state.value.firstOrNull { it.id == id }

	@Synchronized
	fun save(vm: VmConfig) {
		val dir = dir(vm.id)
		dir.mkdirs()
		File(dir, CONFIG).writeAtomically(RuiJson.encodeToString(VmConfig.serializer(), vm))
		state.value = (state.value.filterNot { it.id == vm.id } + vm).sortedBy { it.createdAt }
	}

	fun update(
		id: String,
		change: (VmConfig) -> VmConfig,
	): VmConfig {
		val current = get(id) ?: throw IOException("That machine no longer exists")
		val next = change(current)
		save(next)
		return next
	}

	/** Deletes a machine. With [keepDisks], its disks move to [images] first, so nothing the user made is lost. */
	@Synchronized
	fun delete(
		id: String,
		keepDisks: Boolean,
		images: ImageStore,
	) {
		val vm = get(id) ?: return
		if (keepDisks) {
			for (disk in vm.disks) {
				val file = File(dir(id), disk.file)
				if (file.exists() && disk.role != com.wonderhoylabs.rui.model.DiskRole.SEED) {
					images.adopt(file, "${vm.name} – ${disk.label}")
				}
			}
		}
		dir(id).deleteRecursively()
		state.value = state.value.filterNot { it.id == id }
	}

	fun diskFile(
		vm: VmConfig,
		file: String,
	): File = File(dir(vm.id), file)

	fun usage(vm: VmConfig): DiskUsage =
		vm.disks
			.map { diskFile(vm, it.file).usage() }
			.fold(DiskUsage(0, 0)) { total, disk -> DiskUsage(total.used + disk.used, total.allocated + disk.allocated) }

	companion object {
		const val CONFIG = "config.json"
	}
}

/** Images the user brought in or downloaded, one folder each under `images/`, with `image.json` beside the file. */
class ImageStore(
	private val root: File,
) {
	private val state = MutableStateFlow<List<ImageRecord>>(emptyList())
	val images: StateFlow<List<ImageRecord>> = state.asStateFlow()

	init {
		root.mkdirs()
		reload()
	}

	val dir: File get() = root

	fun reload() {
		state.value =
			root
				.listFiles()
				.orEmpty()
				.filter { it.isDirectory }
				.mapNotNull { dir ->
					runCatching { RuiJson.decodeFromString<ImageRecord>(File(dir, RECORD).readText()) }.getOrNull()
				}.sortedByDescending { it.addedAt }
	}

	fun file(record: ImageRecord): File = File(File(root, record.id), record.file)

	fun get(id: String): ImageRecord? = state.value.firstOrNull { it.id == id }

	@Synchronized
	fun save(record: ImageRecord) {
		val dir = File(root, record.id)
		dir.mkdirs()
		File(dir, RECORD).writeAtomically(RuiJson.encodeToString(ImageRecord.serializer(), record))
		state.value = (state.value.filterNot { it.id == record.id } + record).sortedByDescending { it.addedAt }
	}

	@Synchronized
	fun delete(id: String) {
		File(root, id).deleteRecursively()
		state.value = state.value.filterNot { it.id == id }
	}

	/** A folder for a new image, before its record exists. */
	fun newFolder(id: String): File = File(root, id).also { it.mkdirs() }

	/** Takes over a file from elsewhere in Rui's storage, such as a deleted machine's disk. */
	fun adopt(
		file: File,
		name: String,
	): ImageRecord {
		val id =
			java.util.UUID
				.randomUUID()
				.toString()
		val dir = newFolder(id)
		val target = File(dir, file.name)
		if (!file.renameTo(target)) throw IOException("Couldn't keep ${file.name}")
		val report = runCatching { RuiJson.decodeFromString<com.wonderhoylabs.rui.model.ImageReport>(Native.inspect(target.path)) }.getOrNull()
		val record =
			ImageRecord(
				id = id,
				name = name,
				file = target.name,
				size = target.length(),
				sha256 = "",
				addedAt = System.currentTimeMillis(),
				report = report,
				source = "Kept from a deleted machine",
			)
		save(record)
		return record
	}

	companion object {
		const val RECORD = "image.json"
	}
}
