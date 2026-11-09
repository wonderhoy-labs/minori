package com.wonderhoylabs.rui.setup

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import org.lsposed.hiddenapibypass.HiddenApiBypass
import rikka.shizuku.Shizuku
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * AVF's custom-image calls are hidden API on Android 17, so Rui asks the runtime to let this one app use the
 * `android.system.virtualmachine` package. This changes nothing outside Rui's own process.
 */
object HiddenApi {
	@Volatile var exempted = false
		private set

	fun exempt() {
		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
		exempted =
			runCatching {
				HiddenApiBypass.addHiddenApiExemptions("Landroid/system/virtualmachine/")
				// Only trust it if the hidden class really is reachable now.
				Class
					.forName("android.system.virtualmachine.VirtualMachineCustomImageConfig\$Builder")
					.getMethod("setKernelPath", String::class.java)
				true
			}.getOrDefault(false)
	}
}

enum class ShizukuState {
	NotInstalled,
	NotRunning,
	NeedsPermission,
	Ready,
}

/** Everything the setup screen shows, worked out fresh each time. */
data class PrivilegeState(
	val sdk: Int,
	val buildType: String,
	val avfFeature: Boolean,
	val missing: List<String>,
	val hiddenApi: Boolean,
	val shizuku: ShizukuState,
) {
	val granted: Boolean get() = missing.isEmpty()
	val canRunVms: Boolean get() = avfFeature && granted && hiddenApi && sdk >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
}

object Privileges {
	const val AVF_FEATURE = "android.software.virtualization_framework"
	const val MANAGE = "android.permission.MANAGE_VIRTUAL_MACHINE"
	const val CUSTOM = "android.permission.USE_CUSTOM_VIRTUAL_MACHINE"
	const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"

	/** The two permissions AVF needs: one to run VMs at all, one to boot a kernel that isn't Microdroid. */
	val PERMISSIONS = listOf(MANAGE, CUSTOM)

	fun missingPermissions(context: Context): List<String> =
		PERMISSIONS.filter { context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }

	/** The commands that grant them from a computer, for anyone without Shizuku. */
	fun adbCommands(packageName: String): List<String> = PERMISSIONS.map { "adb shell pm grant $packageName $it" }

	fun grantScript(packageName: String): String = PERMISSIONS.joinToString(" && ") { "pm grant $packageName $it" }

	fun state(context: Context): PrivilegeState =
		PrivilegeState(
			sdk = Build.VERSION.SDK_INT,
			buildType = Build.TYPE,
			avfFeature = context.packageManager.hasSystemFeature(AVF_FEATURE),
			missing = missingPermissions(context),
			hiddenApi = HiddenApi.exempted,
			shizuku = shizukuState(context),
		)

	fun shizukuState(context: Context): ShizukuState {
		val installed = runCatching { context.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0) }.isSuccess
		return when {
			!running() -> if (installed) ShizukuState.NotRunning else ShizukuState.NotInstalled
			Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED -> ShizukuState.NeedsPermission
			else -> ShizukuState.Ready
		}
	}

	private fun running(): Boolean = runCatching { Shizuku.pingBinder() && !Shizuku.isPreV11() }.getOrDefault(false)

	/**
	 * Grants the permissions through Shizuku, then checks they really are granted. Call on a background thread.
	 * Returns null on success, or what went wrong.
	 */
	fun grantWithShizuku(context: Context): String? {
		val output =
			runCatching { ShizukuShell.get(context).run(grantScript(context.packageName)) }
				.getOrElse { return "Shizuku couldn't run the grant: ${it.message}" }
		val missing = missingPermissions(context)
		if (missing.isEmpty()) return null
		val printed = output.trim().lines().dropLast(1).joinToString("\n").trim()
		return if (printed.isNotEmpty()) {
			"Android refused: $printed"
		} else {
			"Android didn't grant ${missing.joinToString { it.substringAfterLast('.') }}."
		}
	}
}

/** The connection to [ShellService], which Shizuku runs as the shell user. */
object ShizukuShell {
	private const val TIMEOUT_SECONDS = 15L
	private const val SERVICE_VERSION = 1

	@Volatile private var service: IShell? = null

	@Volatile private var ready: CountDownLatch? = null

	private val connection =
		object : ServiceConnection {
			override fun onServiceConnected(
				name: ComponentName,
				binder: IBinder?,
			) {
				service = binder?.takeIf { it.pingBinder() }?.let { IShell.Stub.asInterface(it) }
				ready?.countDown()
			}

			override fun onServiceDisconnected(name: ComponentName) {
				service = null
			}
		}

	/** Shizuku reaches a new process a moment after it starts. Call on a background thread. */
	fun awaitRunning(timeoutMs: Long = 3000): Boolean {
		val deadline = SystemClock.uptimeMillis() + timeoutMs
		while (SystemClock.uptimeMillis() < deadline) {
			if (runCatching { Shizuku.pingBinder() }.getOrDefault(false)) return true
			SystemClock.sleep(100)
		}
		return false
	}

	fun get(context: Context): IShell {
		service?.let { return it }
		val latch = CountDownLatch(1)
		ready = latch
		val args =
			Shizuku
				.UserServiceArgs(ComponentName(context.packageName, ShellService::class.java.name))
				.processNameSuffix("shell")
				.version(SERVICE_VERSION)
				.daemon(false)
		Handler(Looper.getMainLooper()).post { Shizuku.bindUserService(args, connection) }
		check(latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) { "Shizuku didn't start Rui's helper" }
		return checkNotNull(service) { "Rui's helper stopped" }
	}
}
