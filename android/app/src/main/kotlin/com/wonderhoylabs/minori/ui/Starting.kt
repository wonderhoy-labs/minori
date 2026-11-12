package com.wonderhoylabs.minori.ui

import android.Manifest
import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.wonderhoylabs.minori.MinoriApp
import com.wonderhoylabs.minori.model.BackendKind
import com.wonderhoylabs.minori.model.VmConfig
import com.wonderhoylabs.minori.service.gib
import com.wonderhoylabs.minori.ui.screens.memoryLabel

// Starting a machine from the app goes through here. It warns first when the machine needs more memory than the phone
// has free, since two big machines at once can leave Android swapping until nothing responds. And Android may stop a
// foreground service that can't show its notification, so the first start asks for notifications, once; Settings has
// the button after that.
private const val PREFS = "notifications"
private const val ASKED = "asked"

/** Whether Minori may show notifications. */
fun notificationsAllowed(context: Context): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
	context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

/**
 * Wraps [start] so that a machine that needs more memory than is free asks first, and the first start asks for
 * notifications too. The machine starts whatever the answer about notifications.
 */
@Composable
fun rememberStartAsking(start: (String) -> Unit): (String) -> Unit {
	val context = LocalContext.current
	val app = MinoriApp.get(context)
	val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
	var tight by remember { mutableStateOf<Pair<VmConfig, Long>?>(null) }
	fun go(id: String) {
		start(id)
		val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
		if (!notificationsAllowed(context) && !prefs.getBoolean(ASKED, false)) {
			prefs.edit().putBoolean(ASKED, true).apply()
			launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
		}
	}
	tight?.let { (vm, free) ->
		AlertDialog(
			onDismissRequest = { tight = null },
			title = { Text("Not enough free memory") },
			text = {
				Text(
					"${vm.name} needs ${memoryLabel(vm.memoryMib)}, and the phone has ${free.gib()} free. Android may " +
						"close apps, and the phone and its machines may slow to a crawl. Stop another machine first, " +
						"or give this one less memory in Edit.",
				)
			},
			confirmButton = {
				TextButton(onClick = {
					tight = null
					go(vm.id)
				}) { Text("Start anyway") }
			},
			dismissButton = { TextButton(onClick = { tight = null }) { Text("Cancel") } },
		)
	}
	return { id ->
		val vm = app.vms.vms.value.firstOrNull { it.id == id }
		val free = vm?.let { memoryShort(context, it) }
		if (vm != null && free != null) tight = vm to free else go(id)
	}
}

/**
 * The phone's free memory when it's less than [vm] needs, from what Android says it could give apps now without
 * swapping, or null when there's enough. A Termux machine is ordinary processes in Termux, with no memory set aside.
 */
fun memoryShort(context: Context, vm: VmConfig): Long? {
	if (vm.backend == BackendKind.TERMUX) return null
	val info = ActivityManager.MemoryInfo()
	context.getSystemService(ActivityManager::class.java).getMemoryInfo(info)
	return info.availMem.takeIf { (vm.memoryMib.toLong() shl 20) > it }
}
