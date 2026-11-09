package com.wonderhoylabs.rui

import android.app.Application
import android.content.Context
import com.wonderhoylabs.rui.backend.AvfHypervisor
import com.wonderhoylabs.rui.backend.DemoHypervisor
import com.wonderhoylabs.rui.backend.DemoTarget
import com.wonderhoylabs.rui.backend.Hypervisor
import com.wonderhoylabs.rui.backend.TermuxHypervisor
import com.wonderhoylabs.rui.model.BackendKind
import com.wonderhoylabs.rui.net.PortForwarder
import com.wonderhoylabs.rui.service.Machines
import com.wonderhoylabs.rui.service.Operations
import com.wonderhoylabs.rui.setup.HiddenApi
import com.wonderhoylabs.rui.store.ImageStore
import com.wonderhoylabs.rui.store.VmStore
import java.io.File

/** Holds the one copy of everything that outlives a screen: the stores, the running machines, their ports. */
class RuiApp : Application() {
	lateinit var vms: VmStore
		private set
	lateinit var images: ImageStore
		private set
	lateinit var machines: Machines
		private set
	lateinit var operations: Operations
		private set

	/** Debug builds: where the demo backend's console and agent point, set by test intents. */
	@Volatile var demoTarget = DemoTarget()

	val storageRoot: File get() = filesDir

	override fun onCreate() {
		super.onCreate()
		HiddenApi.exempt()
		vms = VmStore(File(filesDir, "vms"))
		images = ImageStore(File(filesDir, "images"))
		val backends = mutableMapOf<BackendKind, Hypervisor>(BackendKind.AVF to AvfHypervisor(this))
		backends[BackendKind.TERMUX] = TermuxHypervisor(this)
		if (BuildConfig.DEBUG) backends[BackendKind.DEMO] = DemoHypervisor { demoTarget }
		machines = Machines(this, vms, PortForwarder(), backends)
		operations = Operations(this, vms, images)
	}

	companion object {
		fun get(context: Context): RuiApp = context.applicationContext as RuiApp
	}
}
