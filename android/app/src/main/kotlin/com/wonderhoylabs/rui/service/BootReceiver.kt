package com.wonderhoylabs.rui.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.wonderhoylabs.rui.RuiApp

/** Starts the machines marked "Start when the phone starts". */
class BootReceiver : BroadcastReceiver() {
	override fun onReceive(
		context: Context,
		intent: Intent,
	) {
		if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
		val app = RuiApp.get(context)
		app.vms.vms.value.filter { it.autoStart }.forEach { app.machines.start(it.id) }
	}
}
