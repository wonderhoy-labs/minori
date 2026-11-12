package com.wonderhoylabs.minori.service

import android.content.Context
import com.wonderhoylabs.minori.backend.DemoHypervisor
import com.wonderhoylabs.minori.backend.TermuxHypervisor
import com.wonderhoylabs.minori.model.BackendKind
import com.wonderhoylabs.minori.model.VmConfig
import com.wonderhoylabs.minori.store.VmStore
import com.wonderhoylabs.minori.terminal.Ssh
import java.io.File
import java.io.IOException

/**
 * Adds an Xfce desktop to a Termux machine, the way the store's desktops have one: a TigerVNC session, which Minori's
 * viewer and Asahi draw, and xrdp in front of that same session for Remote Desktop apps. A Termux machine has no
 * systemd, so the minori-desktop loop keeps them running while the machine does, on the machine's own ports on the
 * phone's loopback.
 */
object TermuxDesktop {
	/** The loop in Minori's assets. */
	const val LOOP_ASSET = "minori-desktop.sh"
	private const val DIR = "/root/.config/minori-desktop"

	/** Where Minori puts the loop that keeps the desktop running. */
	const val LOOP = "/root/.local/bin/minori-desktop"

	/**
	 * The distributions Minori knows how to put Xfce, TigerVNC and xrdp on, with the package holding the wallpaper
	 * their Xfce looks for, without which the desktop is black.
	 */
	private val PACKAGES =
		mapOf(
			"debian" to "$APT desktop-base",
			"ubuntu" to "$APT xubuntu-wallpapers",
		)

	private const val APT =
		"apt-get update -q && DEBIAN_FRONTEND=noninteractive apt-get install -y -q --no-install-recommends " +
			"xfce4 xfce4-terminal dbus-x11 tigervnc-standalone-server tigervnc-tools xrdp ssl-cert"

	/** Whether Minori can add a desktop to [vm]. */
	fun canSetUp(vm: VmConfig): Boolean = vm.backend == BackendKind.TERMUX && vm.distro in PACKAGES

	/** Whether [vm] has a desktop from Minori. */
	fun isSetUp(vm: VmConfig): Boolean = vm.backend == BackendKind.TERMUX && vm.access.vncPassword != null

	/**
	 * Installs the desktop in the running Termux machine [vm] over SSH, starts it, and records its ports and VNC
	 * password. Blocks; call on a background thread.
	 */
	fun install(context: Context, vms: VmStore, vm: VmConfig, job: Job) {
		val packages = PACKAGES[vm.distro] ?: throw IOException("Minori can't add a desktop to this distribution yet.")
		val password = vm.access.password ?: throw IOException("${vm.name} has no password set.")
		val ssh = TermuxHypervisor(context).port(vm)
		val vnc = TermuxHypervisor.vncPort(vm)
		val rdp = TermuxHypervisor.rdpPort(vm)
		// VNC passwords are at most 8 characters, and Remote Desktop apps send the same one.
		val vncPassword = password.take(8)

		fun run(command: String, stdin: ByteArray? = null): Ssh.Result = Ssh.run(
			hostAlias = "termux-${vm.id}",
			user = "root",
			password = password,
			privateKey = null,
			knownHosts = File(vms.dir(vm.id), "ssh/known_hosts"),
			open = { DemoHypervisor.tcp("127.0.0.1:$ssh") },
			confirmHostKey = { true },
			command = command,
			stdin = stdin,
		)

		fun check(result: Ssh.Result, what: String) {
			if (result.exitCode != 0) {
				val tail = result.output.trim().lines().takeLast(3).joinToString("\n")
				throw IOException("Minori couldn't $what in ${vm.name}." + if (tail.isNotEmpty()) "\n$tail" else "")
			}
		}

		job.step = "Installing Xfce, TigerVNC and xrdp in ${vm.name}"
		check(run(packages), "install the desktop")
		job.step = "Setting up the desktop"
		check(
			run(
				"mkdir -p $DIR /root/.local/bin && (umask 077 && printf '%s\\n' '$vncPassword' | vncpasswd -f > $DIR/passwd)",
			),
			"set the desktop's password",
		)
		check(run("cat > $DIR/xrdp.ini", xrdpConfig(vnc, rdp).toByteArray()), "set up Remote Desktop")
		val loop = context.assets.open(LOOP_ASSET).use { it.readBytes() }
		check(run("cat > $LOOP && chmod 755 $LOOP", loop), "copy Minori's desktop loop")
		job.step = "Starting the desktop"
		check(run("nohup $LOOP $vnc $rdp $ssh >/dev/null 2>&1 &"), "start the desktop")
		vms.update(vm.id) {
			it.copy(access = it.access.copy(vncPort = vnc, rdpPort = rdp, vncPassword = vncPassword))
		}
	}

	/**
	 * xrdp on the phone's loopback only, going straight to the VNC session with the password the Remote Desktop app
	 * sends. Its own copy, so the distribution's /etc/xrdp stays as it was.
	 */
	private fun xrdpConfig(vnc: Int, rdp: Int): String = """
		[Globals]
		ini_version=1
		fork=true
		port=tcp://127.0.0.1:$rdp
		security_layer=negotiate
		crypt_level=high
		certificate=
		key_file=
		ssl_protocols=TLSv1.2, TLSv1.3
		autorun=minori
		allow_channels=true
		allow_multimon=false
		bitmap_cache=true
		bitmap_compression=true
		bulk_compression=true
		max_bpp=32
		new_cursors=true
		use_fastpath=both

		[Logging]
		LogFile=$DIR/xrdp-session.log
		LogLevel=INFO
		EnableSyslog=false

		[Channels]
		rdpdr=true
		rdpsnd=true
		drdynvc=true
		cliprdr=true
		rail=false
		xrdpvr=false

		[minori]
		name=Minori desktop
		lib=libvnc.so
		username=ask
		password=ask
		ip=127.0.0.1
		port=$vnc
	""".trimIndent() + "\n"
}
