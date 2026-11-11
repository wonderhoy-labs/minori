package com.wonderhoylabs.minori

import android.app.Application
import android.content.Context
import com.wonderhoylabs.minori.backend.AvfHypervisor
import com.wonderhoylabs.minori.backend.DemoHypervisor
import com.wonderhoylabs.minori.backend.DemoTarget
import com.wonderhoylabs.minori.backend.Hypervisor
import com.wonderhoylabs.minori.backend.TermuxHypervisor
import com.wonderhoylabs.minori.model.BackendKind
import com.wonderhoylabs.minori.net.PortForwarder
import com.wonderhoylabs.minori.net.TerminalLink
import com.wonderhoylabs.minori.service.AiriSetup
import com.wonderhoylabs.minori.service.Helper
import com.wonderhoylabs.minori.service.Machines
import com.wonderhoylabs.minori.service.Operations
import com.wonderhoylabs.minori.setup.HiddenApi
import com.wonderhoylabs.minori.store.ImageStore
import com.wonderhoylabs.minori.store.VmStore
import java.io.File

/** Holds the one copy of everything that outlives a screen: the stores, the running machines, their ports. */
class MinoriApp : Application() {
	lateinit var vms: VmStore
		private set
	lateinit var images: ImageStore
		private set
	lateinit var machines: Machines
		private set
	lateinit var operations: Operations
		private set

	/** Debug builds: where the demo backend's console and agent point, set by test intents and kept until changed. */
	var demoTarget: DemoTarget
		get() {
			val prefs = getSharedPreferences("dev", MODE_PRIVATE)
			return DemoTarget(console = prefs.getString("console", null), agent = prefs.getString("agent", null))
		}
		set(value) {
			getSharedPreferences(
				"dev",
				MODE_PRIVATE,
			).edit().putString("console", value.console).putString("agent", value.agent).apply()
		}

	val storageRoot: File get() = filesDir

	/** Carries the traffic of Android's own Linux terminal, when that's on. */
	lateinit var terminal: TerminalLink
		private set

	/** The helper a page reaches over adb. */
	lateinit var helper: Helper
		private set

	override fun onCreate() {
		super.onCreate()
		HiddenApi.exempt()
		vms = VmStore(File(filesDir, "vms"))
		images = ImageStore(File(filesDir, "images"))
		val backends = mutableMapOf<BackendKind, Hypervisor>(BackendKind.AVF to AvfHypervisor(this))
		backends[BackendKind.TERMUX] = TermuxHypervisor(this)
		if (BuildConfig.DEBUG) backends[BackendKind.DEMO] = DemoHypervisor { demoTarget }
		// Machines set up for Airi by Minori 0.2 published a port on the phone; Airi now comes through Minori instead.
		AiriSetup.migrate(vms)
		machines = Machines(this, vms, PortForwarder(), backends)
		operations = Operations(this, vms, images)
		// Before anything that can reach MinoriService, which reads it.
		terminal = TerminalLink(this)
		helper = Helper(this).also { it.start() }
		terminal.resume()
	}

	companion object {
		fun get(context: Context): MinoriApp = context.applicationContext as MinoriApp
	}
}
