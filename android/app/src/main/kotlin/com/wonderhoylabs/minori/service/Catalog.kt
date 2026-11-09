package com.wonderhoylabs.minori.service

import com.wonderhoylabs.minori.core.MinoriJson
import com.wonderhoylabs.minori.core.Native
import com.wonderhoylabs.minori.model.ImageRecord
import com.wonderhoylabs.minori.model.ImageReport
import com.wonderhoylabs.minori.store.ImageStore
import com.wonderhoylabs.minori.store.freeBytes
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** Where an item sits in the store. */
enum class StoreCategory(val label: String) {
	Linux("Linux"),
	Desktop("Desktops"),
	App("Apps"),
}

/** Which Material Symbol an item shows. The store has no artwork of its own. */
enum class StoreIcon {
	Debian,
	Ubuntu,
	Fedora,
	Desktop,
	Browser,
	Terminal,
}

/** A way into a machine that a template sets up, shown on its store page. */
enum class Access(val label: String) {
	Console("Console"),
	Ssh("SSH"),
	Vnc("Desktop over VNC"),
	Rdp("Remote Desktop (RDP)"),
}

/**
 * A machine Minori can make without the user hunting for an image: an official 64-bit Arm cloud image, checked
 * against its project's published checksum, and what to set up on first boot. Desktops and apps are a distro plus
 * packages installed on first boot, so Minori never hosts or redistributes a disk image itself.
 */
data class Template(
	val id: String,
	val name: String,
	val publisher: String,
	val summary: String,
	val about: String,
	val category: StoreCategory,
	val icon: StoreIcon,
	val baseImage: BaseImage,
	val features: List<String>,
	val access: List<Access>,
	/** Roughly what first boot downloads, besides the base image. */
	val extraDownloadMb: Int = 0,
	val memoryMib: Int = 2048,
	val diskGib: Int = 8,
	val packages: List<String> = emptyList(),
	val run: List<String> = emptyList(),
	/** The guest port of a graphical desktop it sets up, if any. */
	val vncPort: Int? = null,
	/** How long first boot takes on a phone, in words. */
	val firstBoot: String = "about two minutes",
)

/** An official image and the checksum list that vouches for it. */
data class BaseImage(
	val id: String,
	val url: String,
	val checksums: String,
	val algorithm: String,
	val file: String,
	val project: String,
	val sizeMb: Int,
)

object Catalog {
	private const val FEDORA = "https://download.fedoraproject.org/pub/fedora/linux/releases/43/Cloud/aarch64/images"

	val debian13 =
		BaseImage(
			id = "debian-13-arm64",
			url = "https://cloud.debian.org/images/cloud/trixie/latest/debian-13-genericcloud-arm64.qcow2",
			checksums = "https://cloud.debian.org/images/cloud/trixie/latest/SHA512SUMS",
			algorithm = "SHA-512",
			file = "debian-13-genericcloud-arm64.qcow2",
			project = "Debian",
			sizeMb = 330,
		)

	private val ubuntu2404 =
		BaseImage(
			id = "ubuntu-24.04-arm64",
			url = "https://cloud-images.ubuntu.com/releases/noble/release/ubuntu-24.04-server-cloudimg-arm64.img",
			checksums = "https://cloud-images.ubuntu.com/releases/noble/release/SHA256SUMS",
			algorithm = "SHA-256",
			file = "ubuntu-24.04-server-cloudimg-arm64.img",
			project = "Ubuntu",
			sizeMb = 600,
		)

	private val fedora43 =
		BaseImage(
			id = "fedora-43-arm64",
			url = "$FEDORA/Fedora-Cloud-Base-Generic-43-1.6.aarch64.qcow2",
			checksums = "$FEDORA/Fedora-Cloud-43-1.6-aarch64-CHECKSUM",
			algorithm = "SHA-256",
			file = "Fedora-Cloud-Base-Generic-43-1.6.aarch64.qcow2",
			project = "Fedora",
			sizeMb = 550,
		)

	/** First boot of a desktop: a VNC server on display :1 and an RDP server, both logging in as the user. */
	private fun desktopRun(session: String): List<String> = listOf(
		"mkdir -p /home/{user}/.config/tigervnc",
		"printf '%s\\n' '{vncpassword}' | vncpasswd -f > /home/{user}/.config/tigervnc/passwd",
		"chmod 600 /home/{user}/.config/tigervnc/passwd",
		"printf 'session=$session\\nlocalhost\\ngeometry=1600x900\\n' > /home/{user}/.config/tigervnc/config",
		"echo ':1={user}' >> /etc/tigervnc/vncserver.users",
		"echo '$session' > /home/{user}/.xsession",
		"chown -R {user}:{user} /home/{user}/.config /home/{user}/.xsession",
		"systemctl enable --now tigervncserver@:1",
		"systemctl enable --now xrdp",
	)

	val templates: List<Template> =
		listOf(
			Template(
				id = "debian-13",
				name = "Debian 13",
				publisher = "The Debian Project",
				summary = "The universal operating system, ready to use in two minutes.",
				about =
					"Debian's official 64-bit Arm cloud image, checked against Debian's own checksums. On first boot " +
						"Minori sets up your user, an SSH key, internet for the guest and its guest agent.",
				category = StoreCategory.Linux,
				icon = StoreIcon.Debian,
				baseImage = debian13,
				features = listOf("Debian 13 \"trixie\"", "OpenSSH server", "apt and the full Debian archive"),
				access = listOf(Access.Console, Access.Ssh),
			),
			Template(
				id = "ubuntu-24.04",
				name = "Ubuntu 24.04 LTS",
				publisher = "Canonical",
				summary = "Long-term support server, with five years of updates.",
				about = "Ubuntu's official 64-bit Arm server cloud image, checked against Ubuntu's own checksums.",
				category = StoreCategory.Linux,
				icon = StoreIcon.Ubuntu,
				baseImage = ubuntu2404,
				features = listOf("Ubuntu Server 24.04 LTS", "OpenSSH server", "snap and apt"),
				access = listOf(Access.Console, Access.Ssh),
			),
			Template(
				id = "fedora-43",
				name = "Fedora 43",
				publisher = "The Fedora Project",
				summary = "The newest Linux, from the people who make it.",
				about =
					"Fedora's official 64-bit Arm cloud image. Its root filesystem is Btrfs, so Minori boots it from " +
						"its separate /boot.",
				category = StoreCategory.Linux,
				icon = StoreIcon.Fedora,
				baseImage = fedora43,
				features = listOf("Fedora Cloud 43", "OpenSSH server", "dnf"),
				access = listOf(Access.Console, Access.Ssh),
			),
			Template(
				id = "kde-desktop",
				name = "KDE Plasma",
				publisher = "Debian 13 with KDE",
				summary = "A full desktop computer on your phone.",
				about =
					"Debian 13 with the KDE Plasma desktop, installed on first boot. Open it in Minori over VNC, or from " +
						"any Remote Desktop app. First boot downloads about 1.5 GB and takes a while; the machine's page " +
						"shows when the desktop is ready.",
				category = StoreCategory.Desktop,
				icon = StoreIcon.Desktop,
				baseImage = debian13,
				features = listOf("KDE Plasma 6", "Firefox ESR", "TigerVNC and xrdp"),
				access = listOf(Access.Vnc, Access.Rdp, Access.Ssh),
				extraDownloadMb = 1500,
				memoryMib = 4096,
				diskGib = 16,
				packages =
					listOf(
						"kde-plasma-desktop",
						"firefox-esr",
						"tigervnc-standalone-server",
						"xrdp",
						"dbus-x11",
					),
				run = desktopRun("startplasma-x11"),
				vncPort = 5901,
				firstBoot = "ten to twenty minutes",
			),
			Template(
				id = "chromium",
				name = "Chromium",
				publisher = "Debian 13 with Chromium",
				summary = "A desktop browser in a window of its own.",
				about =
					"Chromium in a small Openbox desktop on Debian 13, installed on first boot. Open it in Minori over VNC, " +
						"or from any Remote Desktop app.",
				category = StoreCategory.App,
				icon = StoreIcon.Browser,
				baseImage = debian13,
				features = listOf("Chromium", "Openbox", "TigerVNC and xrdp"),
				access = listOf(Access.Vnc, Access.Rdp, Access.Ssh),
				extraDownloadMb = 400,
				memoryMib = 3072,
				diskGib = 10,
				packages =
					listOf(
						"chromium",
						"openbox",
						"tigervnc-standalone-server",
						"xrdp",
						"dbus-x11",
					),
				run =
					listOf(
						"mkdir -p /home/{user}/.config/openbox",
						"echo 'chromium --start-maximized &' > /home/{user}/.config/openbox/autostart",
					) + desktopRun("openbox-session"),
				vncPort = 5901,
				firstBoot = "about five minutes",
			),
		)

	fun template(id: String?): Template? = templates.firstOrNull { it.id == id }

	/**
	 * Downloads a base image into the library, resuming a partial download, and checks it against the project's
	 * checksum list before keeping it. Reuses an earlier download that passed.
	 */
	fun fetch(base: BaseImage, images: ImageStore, job: Job): ImageRecord {
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
			throw IOException("The download doesn't match ${base.project}'s checksum, so Minori deleted it. Try again.")
		}
		if (!partial.renameTo(target)) throw IOException("Couldn't keep the download")
		job.step = "Looking inside"
		val report = MinoriJson.decodeFromString<ImageReport>(Native.inspect(target.path))
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
	fun checksumFor(line: String, file: String): String? {
		val trimmed = line.trim()
		if (trimmed.contains("($file)")) return trimmed.substringAfterLast('=').trim()
		val parts = trimmed.split(Regex("\\s+"))
		if (parts.size == 2 && parts[1].removePrefix("*") == file) return parts[0]
		return null
	}

	private fun download(url: String, partial: File, job: Job, project: String) {
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
