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

/** Whose logo an item shows. [Linux] is for anything else, and has a plain terminal symbol. */
enum class StoreIcon {
	Debian,
	Ubuntu,
	Fedora,
	Kde,
	Xfce,
	Gnome,
	Chromium,
	Firefox,
	LibreOffice,
	Gimp,
	Inkscape,
	Krita,
	Thunderbird,
	Vlc,
	Blender,
	Termux,
	Linux,
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

	// What every desktop and app runs on: one VNC session, which Remote Desktop shows too.
	private val DESKTOP_SERVERS = listOf("tigervnc-standalone-server", "xrdp", "dbus-x11")

	/**
	 * First boot of a desktop: one session on VNC display :1, which Minori opens itself, and xrdp in front of the same
	 * session for Remote Desktop apps. A second session for the same user, which xrdp would otherwise start, breaks
	 * KDE. Remote Desktop logs in with the machine's password, of which VNC checks the first eight characters.
	 * [session] names the session for TigerVNC, and [xsession] is what starts it when TigerVNC doesn't know that name
	 * and falls back to `~/.xsession`.
	 */
	private fun desktopRun(session: String, xsession: String = session): List<String> = listOf(
		"mkdir -p /home/{user}/.config/tigervnc",
		"printf '%s\\n' '{vncpassword}' | vncpasswd -f > /home/{user}/.config/tigervnc/passwd",
		"chmod 600 /home/{user}/.config/tigervnc/passwd",
		"printf 'session=$session\\nlocalhost\\ngeometry=1600x900\\n' > /home/{user}/.config/tigervnc/config",
		"echo ':1={user}' >> /etc/tigervnc/vncserver.users",
		"printf '%s\\n' '$xsession' > /home/{user}/.xsession",
		// Plasma's systemd startup can't start again in a user manager that outlived the last session.
		"printf '[General]\\nsystemdBoot=false\\n' > /home/{user}/.config/startkderc",
		"chown -R {user}:{user} /home/{user}/.config /home/{user}/.xsession",
		// Logging out of the desktop ends the session; this brings up a new one.
		"mkdir -p /etc/systemd/system/tigervncserver@.service.d",
		"printf '[Service]\\nRestart=always\\nRestartSec=3\\n' " +
			"> /etc/systemd/system/tigervncserver@.service.d/minori.conf",
		// TigerVNC's clipboard helper, without the window it otherwise leaves on every desktop.
		"sed -i 's/tigervncconfig -iconic/tigervncconfig -nowin/' /etc/X11/Xtigervnc-session",
		// The desktop's network applet asks for a password in a remote session unless the user may manage
		// NetworkManager, which the guest's sudo user can do anyway.
		"mkdir -p /etc/polkit-1/rules.d",
		"printf '%s\\n' 'polkit.addRule(function (action, subject) {' " +
			"'  if (action.id.indexOf(\"org.freedesktop.NetworkManager.\") === 0 && " +
			"subject.isInGroup(\"sudo\")) return polkit.Result.YES;' '});' > /etc/polkit-1/rules.d/50-minori-network.rules",
		// Remote Desktop goes straight to the VNC session, with the password the RDP app sends.
		"sed -i 's/^autorun=$/autorun=minori/' /etc/xrdp/xrdp.ini",
		"printf '\\n[minori]\\nname=Minori desktop\\nlib=libvnc.so\\n' >> /etc/xrdp/xrdp.ini",
		"printf 'username=ask\\npassword=ask\\nip=127.0.0.1\\nport=5901\\n' >> /etc/xrdp/xrdp.ini",
		"systemctl daemon-reload",
		"systemctl enable --now tigervncserver@:1",
		"systemctl restart xrdp",
		"systemctl enable xrdp",
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
					"Debian 13 with the KDE Plasma desktop, installed on first boot. Open it in Minori, or in any Remote " +
						"Desktop app with the machine's user and password; both show the same desktop. First boot " +
						"downloads about 1.5 GB and takes a while; the machine's page shows when the desktop is ready.",
				category = StoreCategory.Desktop,
				icon = StoreIcon.Kde,
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
				id = "xfce-desktop",
				name = "Xfce",
				publisher = "Debian 13 with Xfce",
				summary = "A light, quick desktop computer.",
				about =
					"Debian 13 with the Xfce desktop, installed on first boot. It's lighter than KDE Plasma and GNOME, so it " +
						"starts faster and leaves the phone more memory. Open it in Minori, or in any Remote Desktop app " +
						"with the machine's user and password; both show the same desktop.",
				category = StoreCategory.Desktop,
				icon = StoreIcon.Xfce,
				baseImage = debian13,
				features = listOf("Xfce 4.20", "Firefox ESR", "TigerVNC and xrdp"),
				access = listOf(Access.Vnc, Access.Rdp, Access.Ssh),
				extraDownloadMb = 700,
				memoryMib = 3072,
				diskGib = 12,
				packages = listOf("xfce4", "xfce4-terminal", "mousepad", "firefox-esr") + DESKTOP_SERVERS,
				run = desktopRun("startxfce4"),
				vncPort = 5901,
				firstBoot = "five to ten minutes",
			),
			Template(
				id = "gnome-desktop",
				name = "GNOME",
				publisher = "Debian 13 with GNOME",
				summary = "Debian's own desktop, the GNOME way.",
				about =
					"Debian 13 with the GNOME desktop, installed on first boot. Open it in Minori, or in any Remote " +
						"Desktop app with the machine's user and password; both show the same desktop. The phone draws " +
						"it without a graphics card, so animations are off. First boot downloads about 1.2 GB.",
				category = StoreCategory.Desktop,
				icon = StoreIcon.Gnome,
				baseImage = debian13,
				features = listOf("GNOME 48", "Firefox ESR", "TigerVNC and xrdp"),
				access = listOf(Access.Vnc, Access.Rdp, Access.Ssh),
				extraDownloadMb = 1200,
				memoryMib = 4096,
				diskGib = 16,
				packages =
					listOf(
						"gnome-session",
						"gnome-shell",
						"gnome-console",
						"nautilus",
						"gnome-text-editor",
						"gnome-control-center",
						"firefox-esr",
					) + DESKTOP_SERVERS,
				run =
					desktopRun(
						"gnome-xorg",
						"export XDG_SESSION_TYPE=x11 XDG_CURRENT_DESKTOP=GNOME; exec gnome-session",
					) +
						// Without a graphics card, animations only make it feel slow. Locking needs GNOME's own login
						// screen, which a VNC session doesn't have, so GNOME would only say it can't.
						"sudo -u {user} dbus-run-session sh -c 'gsettings set org.gnome.desktop.interface " +
						"enable-animations false; gsettings set org.gnome.desktop.screensaver lock-enabled false'",
				vncPort = 5901,
				firstBoot = "ten to twenty minutes",
			),
			app(
				id = "chromium",
				name = "Chromium",
				summary = "A desktop browser in a window of its own.",
				icon = StoreIcon.Chromium,
				packages = listOf("chromium"),
				command = "chromium --start-maximized",
				downloadMb = 400,
			),
			app(
				id = "firefox",
				name = "Firefox",
				summary = "Mozilla's browser, the desktop version.",
				icon = StoreIcon.Firefox,
				packages = listOf("firefox-esr"),
				command = "firefox-esr",
				downloadMb = 300,
				feature = "Firefox ESR",
			),
			app(
				id = "libreoffice",
				name = "LibreOffice",
				summary = "Documents, spreadsheets and slides.",
				icon = StoreIcon.LibreOffice,
				packages = listOf("libreoffice-writer", "libreoffice-calc", "libreoffice-impress", "libreoffice-gtk3"),
				command = "libreoffice",
				downloadMb = 700,
				feature = "Writer, Calc and Impress",
			),
			app(
				id = "gimp",
				name = "GIMP",
				summary = "Edit photos and paint.",
				icon = StoreIcon.Gimp,
				packages = listOf("gimp"),
				command = "gimp",
				downloadMb = 500,
			),
			app(
				id = "inkscape",
				name = "Inkscape",
				summary = "Draw and edit vector graphics.",
				icon = StoreIcon.Inkscape,
				packages = listOf("inkscape"),
				command = "inkscape",
				downloadMb = 500,
			),
			app(
				id = "krita",
				name = "Krita",
				summary = "Digital painting.",
				icon = StoreIcon.Krita,
				packages = listOf("krita"),
				command = "krita",
				downloadMb = 800,
				memoryMib = 4096,
			),
			app(
				id = "thunderbird",
				name = "Thunderbird",
				summary = "Email, calendars and contacts.",
				icon = StoreIcon.Thunderbird,
				packages = listOf("thunderbird"),
				command = "thunderbird",
				downloadMb = 300,
			),
			app(
				id = "vlc",
				name = "VLC",
				summary = "Plays nearly any video or music file.",
				icon = StoreIcon.Vlc,
				packages = listOf("vlc"),
				command = "vlc",
				downloadMb = 300,
			),
			app(
				id = "blender",
				name = "Blender",
				summary = "3D modelling and animation.",
				icon = StoreIcon.Blender,
				packages = listOf("blender"),
				command = "blender",
				downloadMb = 900,
				memoryMib = 4096,
				diskGib = 12,
			),
		)

	fun template(id: String?): Template? = templates.firstOrNull { it.id == id }

	/**
	 * One desktop app alone in a small Openbox desktop, filling it. The app opens again if it's closed, since it's all
	 * the desktop is for. TigerVNC and Remote Desktop show it the same way as a full desktop.
	 */
	private fun app(
		id: String,
		name: String,
		summary: String,
		icon: StoreIcon,
		packages: List<String>,
		command: String,
		downloadMb: Int,
		feature: String = name,
		memoryMib: Int = 3072,
		diskGib: Int = 10,
	) = Template(
		id = id,
		name = name,
		publisher = "Debian 13 with $name",
		summary = summary,
		about =
			"$name on its own in a small desktop on Debian 13, installed on first boot. Open it in Minori, or in any " +
				"Remote Desktop app with the machine's user and password. It opens again if you close it.",
		category = StoreCategory.App,
		icon = icon,
		baseImage = debian13,
		features = listOf(feature, "Openbox", "TigerVNC and xrdp"),
		access = listOf(Access.Vnc, Access.Rdp, Access.Ssh),
		extraDownloadMb = downloadMb,
		memoryMib = memoryMib,
		diskGib = diskGib,
		packages = packages + "openbox" + DESKTOP_SERVERS,
		run =
			listOf(
				"mkdir -p /home/{user}/.config/openbox",
				"printf '%s\\n' '(while true; do $command; sleep 1; done) &' > /home/{user}/.config/openbox/autostart",
				// Its windows fill the desktop without a title bar; dialogs stay as they are.
				"cp /etc/xdg/openbox/rc.xml /home/{user}/.config/openbox/rc.xml",
				"sed -i 's#</applications>#<application class=\"*\" type=\"normal\"><decor>no</decor>" +
					"<maximized>yes</maximized></application></applications>#' /home/{user}/.config/openbox/rc.xml",
			) + desktopRun("openbox-session"),
		vncPort = 5901,
		firstBoot = "about five minutes",
	)

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
		val sums = retrying(base.project, job) { fetchText(base.checksums) }
		val expected =
			sums
				.lines()
				.firstNotNullOfOrNull { line -> checksumFor(line, base.file) }
				?: throw IOException("${base.project}'s checksum list doesn't mention ${base.file}")
		// A retry resumes where the last try stopped, and asks the project's redirector for a mirror again.
		retrying(base.project, job) { download(base.url, partial, job, base.project) }
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

	private const val CONNECT_TIMEOUT_MS = 10_000
	private const val READ_TIMEOUT_MS = 30_000
	private const val TRIES = 4
	private const val RETRY_DELAY_MS = 2_000L

	/**
	 * Runs [attempt] up to [TRIES] times while the network fails. Projects send downloads to mirrors near you, and now
	 * and then one of them can't be reached from a phone's network; asking again usually gets another.
	 */
	private fun <T> retrying(project: String, job: Job, attempt: () -> T): T {
		var last: IOException? = null
		repeat(TRIES) { tryNumber ->
			job.check()
			try {
				return attempt()
			} catch (error: java.net.SocketTimeoutException) {
				last = error
			} catch (error: java.net.ConnectException) {
				last = error
			} catch (error: java.net.UnknownHostException) {
				last = error
			} catch (error: java.net.SocketException) {
				last = error
			}
			if (tryNumber < TRIES - 1) {
				job.step = "$project's server didn't answer. Trying again"
				Thread.sleep(RETRY_DELAY_MS)
			}
		}
		throw IOException(
			"Couldn't reach $project's download server: ${last?.message}. Check the phone's connection and try again.",
			last,
		)
	}

	private fun fetchText(url: String): String {
		val connection = URL(url).openConnection() as HttpURLConnection
		connection.connectTimeout = CONNECT_TIMEOUT_MS
		connection.readTimeout = READ_TIMEOUT_MS
		try {
			return connection.inputStream.use { String(it.readBytes(), Charsets.UTF_8) }
		} finally {
			connection.disconnect()
		}
	}

	private fun download(url: String, partial: File, job: Job, project: String) {
		var have = if (partial.exists()) partial.length() else 0L
		val connection = URL(url).openConnection() as HttpURLConnection
		connection.connectTimeout = CONNECT_TIMEOUT_MS
		connection.readTimeout = READ_TIMEOUT_MS
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
