package com.wonderhoylabs.rui.service

import com.wonderhoylabs.rui.core.Native
import com.wonderhoylabs.rui.core.RuiJson
import com.wonderhoylabs.rui.model.ImageRecord
import com.wonderhoylabs.rui.model.ImageReport
import com.wonderhoylabs.rui.store.ImageStore
import com.wonderhoylabs.rui.store.freeBytes
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

enum class TemplateKind(
	val label: String,
) {
	Distro("Distro"),
	Desktop("Desktop"),
	App("App"),
}

/**
 * A machine Rui can make without the user hunting for an image: an official 64-bit Arm cloud image, checked
 * against its project's published checksum, and what to set up on first boot. Desktops and apps are a distro plus
 * packages installed on first boot, so Rui never hosts or redistributes a disk image itself.
 */
data class Template(
	val id: String,
	val name: String,
	val description: String,
	val kind: TemplateKind,
	val baseImage: BaseImage?,
	val cpusAll: Boolean = true,
	val memoryMib: Int = 2048,
	val diskGib: Int = 8,
	val packages: List<String> = listOf("openssh-server"),
	val run: List<String> = emptyList(),
	/** The guest port of a graphical desktop it sets up, if any. */
	val vncPort: Int? = null,
	/** Why it can't be made, in words for the user, when it can't. */
	val unavailable: String? = null,
	/** What hasn't been seen to work yet, shown on the card. */
	val caveat: String? = null,
)

/** An official image and the checksum list that vouches for it. */
data class BaseImage(
	val id: String,
	val url: String,
	val checksums: String,
	val algorithm: String,
	val file: String,
	val project: String,
)

object Catalog {
	val debian13 =
		BaseImage(
			id = "debian-13-arm64",
			url = "https://cloud.debian.org/images/cloud/trixie/latest/debian-13-genericcloud-arm64.qcow2",
			checksums = "https://cloud.debian.org/images/cloud/trixie/latest/SHA512SUMS",
			algorithm = "SHA-512",
			file = "debian-13-genericcloud-arm64.qcow2",
			project = "Debian",
		)

	private val ubuntu2404 =
		BaseImage(
			id = "ubuntu-24.04-arm64",
			url = "https://cloud-images.ubuntu.com/releases/noble/release/ubuntu-24.04-server-cloudimg-arm64.img",
			checksums = "https://cloud-images.ubuntu.com/releases/noble/release/SHA256SUMS",
			algorithm = "SHA-256",
			file = "ubuntu-24.04-server-cloudimg-arm64.img",
			project = "Ubuntu",
		)

	private val fedora43 =
		BaseImage(
			id = "fedora-43-arm64",
			url = "https://download.fedoraproject.org/pub/fedora/linux/releases/43/Cloud/aarch64/images/Fedora-Cloud-Base-Generic-43-1.6.aarch64.qcow2",
			checksums = "https://download.fedoraproject.org/pub/fedora/linux/releases/43/Cloud/aarch64/images/Fedora-Cloud-43-1.6-aarch64-CHECKSUM",
			algorithm = "SHA-256",
			file = "Fedora-Cloud-Base-Generic-43-1.6.aarch64.qcow2",
			project = "Fedora",
		)

	private const val VNC_SETUP =
		"mkdir -p /home/{user}/.config/tigervnc && " +
			"printf '%s\\n' '{vncpassword}' | vncpasswd -f > /home/{user}/.config/tigervnc/passwd && " +
			"chmod 600 /home/{user}/.config/tigervnc/passwd && chown -R {user}:{user} /home/{user}/.config && " +
			"echo ':1={user}' >> /etc/tigervnc/vncserver.users && " +
			"printf 'session={session}\\nlocalhost\\ngeometry=1600x900\\n' > /home/{user}/.config/tigervnc/config && " +
			"chown {user}:{user} /home/{user}/.config/tigervnc/config && systemctl enable --now tigervncserver@:1"

	val templates: List<Template> =
		listOf(
			Template(
				id = "debian-13",
				name = "Debian 13",
				description = "Debian's official 64-bit Arm cloud image, with a user, SSH and Rui's guest agent set up on first boot.",
				kind = TemplateKind.Distro,
				baseImage = debian13,
			),
			Template(
				id = "ubuntu-24.04",
				name = "Ubuntu 24.04 LTS",
				description = "Ubuntu's official 64-bit Arm server cloud image.",
				kind = TemplateKind.Distro,
				baseImage = ubuntu2404,
				caveat = "Not booted under AVF yet.",
			),
			Template(
				id = "fedora-43",
				name = "Fedora 43",
				description = "Fedora's official 64-bit Arm cloud image. Its root is Btrfs, so Rui boots it from the separate /boot.",
				kind = TemplateKind.Distro,
				baseImage = fedora43,
				caveat = "Not booted under AVF yet.",
			),
			Template(
				id = "arch",
				name = "Arch Linux",
				description = "Arch Linux ARM.",
				kind = TemplateKind.Distro,
				baseImage = null,
				unavailable =
					"Arch Linux ARM publishes a root filesystem archive, not a disk image, and Rui can't build a disk " +
						"from one yet. On the Termux backend, proot-distro can install Arch.",
			),
			Template(
				id = "kde-desktop",
				name = "KDE Plasma desktop",
				description = "Debian 13 with KDE Plasma 6, shown through VNC. First boot downloads about 1.5 GB of packages.",
				kind = TemplateKind.Desktop,
				baseImage = debian13,
				memoryMib = 4096,
				diskGib = 16,
				packages = listOf("openssh-server", "kde-plasma-desktop", "tigervnc-standalone-server", "dbus-x11"),
				run = listOf(VNC_SETUP.replace("{session}", "plasmax11")),
				vncPort = 5901,
				caveat = "Not run on a phone yet.",
			),
			Template(
				id = "chromium",
				name = "Chromium browser",
				description = "Debian 13 with only Chromium in a small window manager, shown through VNC.",
				kind = TemplateKind.App,
				baseImage = debian13,
				memoryMib = 3072,
				diskGib = 10,
				packages = listOf("openssh-server", "chromium", "openbox", "tigervnc-standalone-server", "dbus-x11"),
				run =
					listOf(
						"mkdir -p /home/{user}/.config/openbox && echo 'chromium --start-maximized &' > /home/{user}/.config/openbox/autostart && chown -R {user}:{user} /home/{user}/.config",
						VNC_SETUP.replace("{session}", "openbox"),
					),
				vncPort = 5901,
				caveat = "Not run on a phone yet.",
			),
			Template(
				id = "windows",
				name = "Windows 11 on Arm",
				description = "Microsoft Windows.",
				kind = TemplateKind.Desktop,
				baseImage = null,
				unavailable =
					"AVF starts a Linux kernel directly and has no UEFI firmware, TPM or display Windows could boot " +
						"with, and Microsoft offers no image Rui may download for you. Rui doesn't offer it.",
			),
		)

	/**
	 * Downloads a base image into the library, resuming a partial download, and checks it against the project's
	 * checksum list before keeping it. Reuses an earlier download that passed.
	 */
	fun fetch(
		base: BaseImage,
		images: ImageStore,
		job: Job,
	): ImageRecord {
		images.images.value
			.firstOrNull { it.id == base.id && images.file(it).exists() }
			?.let { return it }
		val dir = images.newFolder(base.id)
		val target = File(dir, base.file)
		val partial = File(dir, "${base.file}.part")
		job.step = "Getting the checksum list from ${base.project}"
		val sums = URL(base.checksums).readText()
		val expected =
			sums
				.lines()
				.firstNotNullOfOrNull { line -> checksumFor(line, base.file) }
				?: throw IOException("${base.project}'s checksum list doesn't mention ${base.file}")
		download(base.url, partial, job, base.project)
		job.step = "Checking the download"
		val digest = MessageDigest.getInstance(base.algorithm)
		partial.inputStream().use { input ->
			val buffer = ByteArray(1 shl 20)
			job.done = 0
			while (true) {
				job.check()
				val n = input.read(buffer)
				if (n < 0) break
				digest.update(buffer, 0, n)
				job.done += n
			}
		}
		val actual = digest.digest().joinToString("") { "%02x".format(it) }
		if (!actual.equals(expected, ignoreCase = true)) {
			partial.delete()
			throw IOException("The download doesn't match ${base.project}'s checksum, so Rui deleted it. Try again.")
		}
		if (!partial.renameTo(target)) throw IOException("Couldn't keep the download")
		job.step = "Looking inside"
		val report = RuiJson.decodeFromString<ImageReport>(Native.inspect(target.path))
		val record =
			ImageRecord(
				id = base.id,
				name = base.file,
				file = base.file,
				size = target.length(),
				sha256 = if (base.algorithm == "SHA-256") actual else "",
				addedAt = System.currentTimeMillis(),
				report = report,
				source = "Downloaded from ${URL(base.url).host}, ${base.algorithm} checked",
			)
		images.save(record)
		return record
	}

	/** Reads one line of a checksum list in either GNU (`hash  name`) or BSD (`SHA256 (name) = hash`) form. */
	fun checksumFor(
		line: String,
		file: String,
	): String? {
		val trimmed = line.trim()
		if (trimmed.contains("($file)")) return trimmed.substringAfterLast('=').trim()
		val parts = trimmed.split(Regex("\\s+"))
		if (parts.size == 2 && parts[1].removePrefix("*") == file) return parts[0]
		return null
	}

	private fun download(
		url: String,
		partial: File,
		job: Job,
		project: String,
	) {
		var have = if (partial.exists()) partial.length() else 0L
		val connection = URL(url).openConnection() as HttpURLConnection
		connection.connectTimeout = 20_000
		connection.readTimeout = 60_000
		connection.instanceFollowRedirects = true
		if (have > 0) connection.setRequestProperty("Range", "bytes=$have-")
		connection.connect()
		val code = connection.responseCode
		if (code == HttpURLConnection.HTTP_OK) {
			have = 0
		} else if (code != HttpURLConnection.HTTP_PARTIAL) {
			throw IOException("$project's server answered $code")
		}
		val length = connection.contentLengthLong
		val total = if (length > 0) have + length else -1
		if (total > 0 && total - have > freeBytes(partial.parentFile ?: partial)) {
			throw IOException("There isn't enough space for the download: it needs ${total.gib()}.")
		}
		job.step = "Downloading from $project"
		job.total = total.coerceAtLeast(0)
		job.done = have
		RandomAccessFile(partial, "rw").use { out ->
			out.setLength(have)
			out.seek(have)
			connection.inputStream.use { input ->
				val buffer = ByteArray(256 * 1024)
				while (true) {
					job.check()
					val n = input.read(buffer)
					if (n < 0) break
					out.write(buffer, 0, n)
					job.done += n
				}
			}
		}
	}
}
