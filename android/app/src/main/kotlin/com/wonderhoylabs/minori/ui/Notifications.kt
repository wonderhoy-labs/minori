package com.wonderhoylabs.minori.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

// Android may stop a foreground service that can't show its notification, so the first time someone starts a machine,
// Minori asks for notifications. It asks once; Settings has the button after that.
private const val PREFS = "notifications"
private const val ASKED = "asked"

/** Whether Minori may show notifications. */
fun notificationsAllowed(context: Context): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
	context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

/**
 * Wraps [start] so that the first start asks for notifications too. The machine starts either way, whatever the
 * answer.
 */
@Composable
fun rememberStartAsking(start: (String) -> Unit): (String) -> Unit {
	val context = LocalContext.current
	val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
	return { id ->
		start(id)
		val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
		if (!notificationsAllowed(context) && !prefs.getBoolean(ASKED, false)) {
			prefs.edit().putBoolean(ASKED, true).apply()
			launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
		}
	}
}
