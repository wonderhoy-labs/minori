package com.wonderhoylabs.minori.ui

import android.app.Activity
import android.os.Bundle
import android.widget.Toast
import com.wonderhoylabs.minori.MinoriApp
import com.wonderhoylabs.minori.R
import com.wonderhoylabs.minori.service.AiriSetup
import com.wonderhoylabs.minori.ui.screens.memoryLabel

/**
 * Starts a machine for Airi, when someone opens one of its apps while it's stopped, and closes again at once without
 * showing anything. It's an activity because Android only lets an app on screen start the foreground service that keeps
 * a machine running. Airi then waits for its agent through Minori's provider.
 */
class AiriStartActivity : Activity() {
	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		val app = MinoriApp.get(this)
		val id = intent?.getStringExtra(EXTRA_MACHINE)
		val vm = id?.let(app.vms::get)
		when {
			vm == null -> Toast.makeText(this, getString(R.string.airi_no_machine), Toast.LENGTH_LONG).show()

			!AiriSetup.isSetUp(
				vm,
			) -> Toast.makeText(this, getString(R.string.app_link_needs_airi, vm.name), Toast.LENGTH_LONG).show()

			memoryShort(this, vm) != null ->
				Toast.makeText(
					this,
					getString(R.string.app_link_low_memory, vm.name, memoryLabel(vm.memoryMib)),
					Toast.LENGTH_LONG,
				).show()

			else -> app.machines.start(vm.id)
		}
		finish()
	}

	companion object {
		const val ACTION = "com.wonderhoylabs.minori.action.START_FOR_AIRI"
		const val EXTRA_MACHINE = "machine"
	}
}
