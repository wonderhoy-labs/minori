package com.wonderhoylabs.minori.service

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import com.wonderhoylabs.minori.core.MinoriJson
import com.wonderhoylabs.minori.core.Native
import com.wonderhoylabs.minori.core.NativeProgress
import com.wonderhoylabs.minori.model.AccessSettings
import com.wonderhoylabs.minori.model.BackendKind
import com.wonderhoylabs.minori.model.BootSettings
import com.wonderhoylabs.minori.model.CpuMode
import com.wonderhoylabs.minori.model.DiskRole
import com.wonderhoylabs.minori.model.ImageRecord
import com.wonderhoylabs.minori.model.ImageReport
import com.wonderhoylabs.minori.model.VmConfig
import com.wonderhoylabs.minori.model.VmDisk
import com.wonderhoylabs.minori.store.ImageStore
import com.wonderhoylabs.minori.store.VmStore
import com.wonderhoylabs.minori.store.freeBytes
import com.wonderhoylabs.minori.terminal.Ssh
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID

/** Progress for the UI: what's happening, and how far along. Return false from [cancelled] checks to stop. */
class Job(val title: String) {
	@Volatile var step: String = ""

	@Volatile var done: Long = 0

	@Volatile var total: Long = 0

	@Volatile var cancelled = false

	val fraction: Float get() = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else 0f

	fun native(step: String): NativeProgress {
		this.step = step
		return NativeProgress { done, total ->
			this.done = done
			this.total = total
			!cancelled
		}
	}

	fun check() {
		if (cancelled) throw IOException("Cancelled")
	}
}

/** What to set up on a cloud image's first boot. */
@Serializable
data class FirstBoot(
	val hostname: String,
	val user: String,
	val password: String?,
	val generateKey: Boolean = true,
	/** Packages to install once the guest has internet, which comes through its agent. */
	val packages: List<String> = emptyList(),
	val installAgent: Boolean = true,
	val extraRun: List<String> = emptyList(),
)

/** Everything that makes, changes or moves machines and images. All of it blocks; call on a background thread. */
class Operations(private val context: Context, private val vms: VmStore, private val images: ImageStore) {
	/** Brings a file the user picked into the image library: copies it with a checksum, then inspects it. */
	fun importImage(uri: Uri, job: Job): ImageRecord {
		val resolver = context.contentResolver
		var name = "image"
		var size = -1L
		resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
			if (cursor.moveToFirst()) {
				name = cursor.getString(0) ?: name
				if (!cursor.isNull(1)) size = cursor.getLong(1)
			}
		}
		val free = freeBytes(images.dir)
		if (size > 0 && size + SPARE > free) {
			throw IOException(
				"There isn't enough space: ${name.take(40)} needs ${size.gib()} and the phone has ${free.gib()} free.",
			)
		}
		val id = UUID.randomUUID().toString()
		val dir = images.newFolder(id)
		val target = File(dir, safeName(name))
		try {
			job.step = "Copying $name"
			job.total = size.coerceAtLeast(0)
			val digest = MessageDigest.getInstance("SHA-256")
			(resolver.openInputStream(uri) ?: throw IOException("Couldn't open $name")).use { input ->
				target.outputStream().use { output ->
					val buffer = ByteArray(1 shl 20)
					while (true) {
						job.check()
						val n = input.read(buffer)
						if (n < 0) break
						digest.update(buffer, 0, n)
						output.write(buffer, 0, n)
						job.done += n
					}
				}
			}
			job.step = "Looking inside"
			val report = MinoriJson.decodeFromString<ImageReport>(Native.inspect(target.path))
			val record =
				ImageRecord(
					id = id,
					name = name,
					file = target.name,
					size = target.length(),
					sha256 = digest.digest().joinToString("") { "%02x".format(it) },
					addedAt = System.currentTimeMillis(),
					report = report,
					source = "Picked from ${uri.authority ?: "a file"}",
				)
			images.save(record)
			return record
		} catch (error: Throwable) {
			dir.deleteRecursively()
			throw error
		}
	}

	/** Makes a machine from an image in the library. Cloud images get a seed for [firstBoot]. */
	fun createFromImage(
		name: String,
		image: ImageRecord,
		backend: BackendKind,
		cpu: CpuMode,
		memoryMib: Int,
		diskGib: Int,
		firstBoot: FirstBoot?,
		job: Job,
		template: String? = null,
	): VmConfig {
		val report = image.report ?: MinoriJson.decodeFromString(Native.inspect(images.file(image).path))
		if (report.verdict ==
			"wrong_arch"
		) {
			throw IOException("${image.name} is for x86-64 PCs. A phone can only run 64-bit Arm.")
		}
		if (report.verdict == "windows") throw IOException("Minori can't boot Windows. See the catalog for why.")
		val id = UUID.randomUUID().toString()
		val dir = vms.dir(id).apply { mkdirs() }
		try {
			val source = images.file(image)
			val disks = mutableListOf<VmDisk>()
			val sizeBytes = diskGib.toLong() * GIB
			val bootDisk: VmDisk
			if (report.format == "iso") {
				val root = VmDisk(file = "root.img", label = "System disk", role = DiskRole.ROOT)
				job.step = "Making an empty system disk"
				Native.createBlank(File(dir, root.file).path, sizeBytes)
				val iso = VmDisk(file = "install.iso", label = image.name, role = DiskRole.CDROM, readOnly = true, format = "iso")
				Native.toRaw(source.path, File(dir, iso.file).path, job.native("Copying the ISO"))
				disks += root
				disks += iso
				bootDisk = iso
			} else {
				val root = VmDisk(file = "root.img", label = "System disk", role = DiskRole.ROOT)
				val written = Native.toRaw(source.path, File(dir, root.file).path, job.native("Writing the system disk"))
				if (sizeBytes > written) Native.grow(File(dir, root.file).path, sizeBytes)
				disks += root
				bootDisk = root
			}
			job.check()
			var boot: BootSettings? = null
			val plan = report.boot
			if (plan != null) {
				job.step = "Finding the kernel"
				Native.extractBoot(
					File(dir, bootDisk.file).path,
					MinoriJson.encodeToString(com.wonderhoylabs.minori.model.BootPlan.serializer(), plan),
					File(dir, "boot").path,
				)
				boot =
					BootSettings(
						diskId = bootDisk.id,
						plan = plan,
						initrdFile = if (plan.initrd != null) "boot/initrd" else null,
						cmdline = plan.cmdline,
					)
			}
			var access = AccessSettings()
			var agent = false
			if (firstBoot != null && report.format != "iso") {
				job.step = "Writing the first-boot settings"
				val key = if (firstBoot.generateKey) Ssh.generateKey("minori@${firstBoot.hostname}") else null
				key?.let { File(dir, "ssh/id_rsa").apply { parentFile?.mkdirs() }.writeText(it.privateKey) }
				val seed = VmDisk(file = "seed.img", label = "First-boot settings", role = DiskRole.SEED, readOnly = true)
				Native.writeSeed(File(dir, seed.file).path, cloudConfig(firstBoot, key?.publicKey), id, null)
				disks += seed
				access = AccessSettings(user = firstBoot.user, password = firstBoot.password)
				agent = firstBoot.installAgent
			}
			val vm =
				VmConfig(
					id = id,
					name = name,
					backend = backend,
					cpu = cpu,
					memoryMib = memoryMib,
					disks = disks,
					boot = boot,
					access = access,
					os = report.os,
					template = template,
					agent = agent,
				)
			vms.save(vm)
			return vm
		} catch (error: Throwable) {
			dir.deleteRecursively()
			throw error
		}
	}

	/** The cloud-init settings, with minori-agent and its service written in, as `minori_core::seed::CloudConfig` JSON. */
	private fun cloudConfig(firstBoot: FirstBoot, publicKey: String?): String {
		val files = mutableListOf<JsonObject>()
		val run = mutableListOf<String>()
		if (firstBoot.installAgent) {
			val agent = context.assets.open(AGENT_ASSET).use { it.readBytes() }
			files +=
				buildJsonObject {
					put("path", "/usr/local/bin/minori-agent")
					put("permissions", "0755")
					put("encoding", "b64")
					put("content", Base64.encodeToString(agent, Base64.NO_WRAP))
				}
			files +=
				buildJsonObject {
					put("path", "/etc/systemd/system/minori-agent.service")
					put("permissions", "0644")
					put("content", AGENT_UNIT)
				}
			run += "systemctl daemon-reload"
			run += "systemctl enable --now minori-agent.service"
		}
		files +=
			buildJsonObject {
				put("path", "/etc/profile.d/minori-console-size.sh")
				put("permissions", "0644")
				put("content", context.assets.open(CONSOLE_SIZE_ASSET).use { it.readBytes().toString(Charsets.UTF_8) })
			}
		if (firstBoot.packages.isNotEmpty()) {
			// cloud-init's own package step runs before the agent, and so before the guest has internet. Install here
			// instead, once the agent has started and DNS answers.
			run += "for i in \$(seq 1 120); do getent hosts deb.debian.org >/dev/null && break; sleep 5; done"
			run += "apt-get update"
			run += "DEBIAN_FRONTEND=noninteractive apt-get install -y ${firstBoot.packages.joinToString(" ")}"
		}
		run += firstBoot.extraRun
		run += "touch /var/lib/minori-ready"
		return buildJsonObject {
			put("hostname", firstBoot.hostname)
			put("user", firstBoot.user)
			firstBoot.password?.let { put("password", it) }
			put("ssh_keys", buildJsonArray { publicKey?.let { add(kotlinx.serialization.json.JsonPrimitive(it)) } })
			put("files", buildJsonArray { files.forEach { add(it) } })
			put("run", buildJsonArray { run.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } })
		}.toString()
	}

	/** Adds an empty data disk. */
	fun addBlankDisk(vm: VmConfig, label: String, sizeGib: Int): VmConfig {
		val disk = VmDisk(file = "disk-${UUID.randomUUID().toString().take(8)}.img", label = label, role = DiskRole.DATA)
		Native.createBlank(vms.diskFile(vm, disk.file).path, sizeGib.toLong() * GIB)
		return vms.update(vm.id) { it.copy(disks = it.disks + disk) }
	}

	/** Attaches an image from the library as another disk, copied so the library keeps its own. */
	fun attachImage(vm: VmConfig, image: ImageRecord, job: Job): VmConfig {
		val iso = image.report?.format == "iso"
		val disk =
			VmDisk(
				file = "disk-${UUID.randomUUID().toString().take(8)}.${if (iso) "iso" else "img"}",
				label = image.name,
				role = if (iso) DiskRole.CDROM else DiskRole.DATA,
				readOnly = iso,
				format = if (iso) "iso" else "raw",
			)
		Native.toRaw(images.file(image).path, vms.diskFile(vm, disk.file).path, job.native("Copying ${image.name}"))
		return vms.update(vm.id) { it.copy(disks = it.disks + disk) }
	}

	fun removeDisk(vm: VmConfig, disk: VmDisk): VmConfig {
		if (vm.boot?.diskId ==
			disk.id
		) {
			throw IOException("That's the disk ${vm.name} boots from. Choose another boot disk first.")
		}
		vms.diskFile(vm, disk.file).delete()
		return vms.update(vm.id) { it.copy(disks = it.disks.filterNot { d -> d.id == disk.id }) }
	}

	fun growDisk(vm: VmConfig, disk: VmDisk, sizeGib: Int): VmConfig {
		Native.grow(vms.diskFile(vm, disk.file).path, sizeGib.toLong() * GIB)
		return vms.update(vm.id) { it }
	}

	/** Boots from another disk: finds its kernel and extracts it again. */
	fun setBootDisk(vm: VmConfig, disk: VmDisk): VmConfig {
		val path = vms.diskFile(vm, disk.file).path
		val report = MinoriJson.decodeFromString<ImageReport>(Native.inspect(path))
		val plan = report.boot ?: throw IOException("Minori found no kernel it can boot on ${disk.label}.")
		Native.extractBoot(
			path,
			MinoriJson.encodeToString(com.wonderhoylabs.minori.model.BootPlan.serializer(), plan),
			File(vms.dir(vm.id), "boot").path,
		)
		return vms.update(vm.id) {
			it.copy(
				boot = BootSettings(
					diskId = disk.id,
					plan = plan,
					initrdFile = if (plan.initrd !=
						null
					) {
						"boot/initrd"
					} else {
						null
					},
					cmdline = plan.cmdline,
				),
			)
		}
	}

	/** Copies a machine, disks and all. Sparse disks stay sparse. */
	fun clone(vm: VmConfig, job: Job): VmConfig {
		val id = UUID.randomUUID().toString()
		val from = vms.dir(vm.id)
		val to = vms.dir(id).apply { mkdirs() }
		try {
			for (disk in vm.disks) {
				job.check()
				Native.toRaw(File(from, disk.file).path, File(to, disk.file).path, job.native("Copying ${disk.label}"))
			}
			File(from, "boot").takeIf { it.exists() }?.copyRecursively(File(to, "boot"))
			File(from, "ssh").takeIf { it.exists() }?.copyRecursively(File(to, "ssh"))
			val copy = vm.copy(
				id = id,
				name = "${vm.name} copy",
				createdAt = System.currentTimeMillis(),
				network = vm.network.copy(forwards = emptyList()),
			)
			vms.save(copy)
			return copy
		} catch (error: Throwable) {
			to.deleteRecursively()
			throw error
		}
	}

	/**
	 * Exports a machine as a `.minorivm` archive into [outFd] (a file descriptor the user picked through the system's
	 * file picker). Passwords and keys stay on the phone unless [includeSecrets].
	 */
	fun export(vm: VmConfig, outFd: Int, includeSecrets: Boolean, job: Job) {
		val stored = if (includeSecrets) vm else vm.copy(access = vm.access.copy(password = null, vncPassword = null))
		val json = MinoriJson.encodeToString(VmConfig.serializer(), stored)
		val disks =
			buildJsonArray {
				vm.disks.forEach { disk ->
					add(
						buildJsonObject {
							put("name", disk.file)
							put("path", vms.diskFile(vm, disk.file).path)
						},
					)
				}
			}.toString()
		Native.exportArchive(json, disks, outFd, job.native("Packing ${vm.name}"))
	}

	/** Imports a `.minorivm` archive from [inFd] as a new machine. */
	fun importArchive(inFd: Int, total: Long, job: Job): VmConfig {
		val id = UUID.randomUUID().toString()
		val dir = vms.dir(id)
		try {
			val manifest = MinoriJson.parseToJsonElement(
				Native.importArchive(inFd, total, dir.path, job.native("Unpacking")),
			).jsonObject
			val stored = MinoriJson.decodeFromJsonElement(
				VmConfig.serializer(),
				manifest["vm"] ?: throw IOException("The archive has no VM settings"),
			)
			var vm = stored.copy(id = id, createdAt = System.currentTimeMillis())
			// The kernel isn't in the archive; it comes from the boot disk again.
			val boot = vm.boot
			if (boot != null) {
				val disk = vm.disks.firstOrNull { it.id == boot.diskId }
				if (disk != null) {
					Native.extractBoot(
						File(dir, disk.file).path,
						MinoriJson.encodeToString(com.wonderhoylabs.minori.model.BootPlan.serializer(), boot.plan),
						File(dir, "boot").path,
					)
				}
			}
			if (vms.vms.value.any { it.name == vm.name }) vm = vm.copy(name = "${vm.name} (imported)")
			vms.save(vm)
			return vm
		} catch (error: Throwable) {
			dir.deleteRecursively()
			throw error
		}
	}

	companion object {
		const val GIB = 1024L * 1024 * 1024
		private const val SPARE = 256L * 1024 * 1024
		const val AGENT_ASSET = "minori-agent-aarch64"
		const val CONSOLE_SIZE_ASSET = "minori-console-size.sh"
		const val AGENT_UNIT =
			"[Unit]\nDescription=Minori guest agent\nAfter=network.target\n\n" +
				"[Service]\nExecStart=/usr/local/bin/minori-agent\n" +
				"Restart=always\nRestartSec=2\n\n[Install]\nWantedBy=multi-user.target\n"

		fun safeName(name: String): String = name.replace(Regex("[^A-Za-z0-9._-]"), "_").take(100).ifEmpty { "image" }

		/** A password that's easy to read aloud and copy: four groups of letters and digits. */
		fun generatePassword(): String {
			val alphabet = "abcdefghjkmnpqrstuvwxyz23456789"
			val random = SecureRandom()
			return (1..4).joinToString("-") { (1..4).map { alphabet[random.nextInt(alphabet.length)] }.joinToString("") }
		}
	}
}

/** A size for people: GiB from 1 GiB, MiB from 1 MiB, KiB below that. */
fun Long.gib(): String = when {
	this >= Operations.GIB -> "%.1f GiB".format(this.toDouble() / Operations.GIB)
	this >= 1024 * 1024 -> "%d MiB".format(this / (1024 * 1024))
	else -> "%d KiB".format(this / 1024)
}
