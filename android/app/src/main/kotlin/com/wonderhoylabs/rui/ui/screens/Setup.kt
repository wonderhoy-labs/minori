package com.wonderhoylabs.rui.ui.screens

import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.wonderhoylabs.rui.BuildConfig
import com.wonderhoylabs.rui.setup.Privileges
import com.wonderhoylabs.rui.setup.ShizukuState
import com.wonderhoylabs.rui.ui.AppModel
import com.wonderhoylabs.rui.ui.CommandBox
import com.wonderhoylabs.rui.ui.Fact
import com.wonderhoylabs.rui.ui.Section
import com.wonderhoylabs.rui.ui.SubScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku

/**
 * The first-run check: does this phone have AVF, does Rui have its two permissions, and if not, grant them
 * through Shizuku or show the adb commands. It checks again whenever the app comes back and every few seconds.
 */
@Composable
fun SetupScreen(model: AppModel) {
	val context = LocalContext.current
	var state by remember { mutableStateOf(Privileges.state(context)) }
	var working by remember { mutableStateOf(false) }
	var result by remember { mutableStateOf<String?>(null) }
	LifecycleResumeEffect(Unit) {
		state = Privileges.state(context)
		onPauseOrDispose {}
	}
	LaunchedEffect(Unit) {
		while (true) {
			delay(2000)
			state = withContext(Dispatchers.IO) { Privileges.state(context) }
		}
	}
	DisposableEffect(Unit) {
		val listener = Shizuku.OnRequestPermissionResultListener { _, _ -> state = Privileges.state(context) }
		runCatching { Shizuku.addRequestPermissionResultListener(listener) }
		onDispose { runCatching { Shizuku.removeRequestPermissionResultListener(listener) } }
	}
	var grantRequested by remember { mutableStateOf(false) }
	LaunchedEffect(grantRequested, state.shizuku) {
		if (!grantRequested || state.shizuku != ShizukuState.Ready || state.granted) return@LaunchedEffect
		working = true
		result = withContext(Dispatchers.IO) { Privileges.grantWithShizuku(context) }
		state = Privileges.state(context)
		working = false
		grantRequested = false
	}

	SubScreen(title = "Set up Rui", model = model) {
		Section(title = "This phone", highlight = state.canRunVms) {
			Fact("Android", "${Build.VERSION.RELEASE} (API ${state.sdk}), ${state.buildType} build")
			Fact("Virtualization Framework", if (state.avfFeature) "Present" else "Not on this device")
			Fact("Rui can boot your own kernels", if (state.hiddenApi) "Yes" else "No")
			if (state.canRunVms) {
				Text("Rui is ready to run virtual machines.", style = MaterialTheme.typography.bodyLarge)
			}
		}
		if (!state.avfFeature) {
			Section(title = "No hypervisor here") {
				Text(
					"This device doesn't have Android's Virtualization Framework, so Rui can't run virtual machines on it. " +
						"Pixel 6 and newer phones on Android 14 or later have it; emulators and most other phones don't.",
				)
				Text(
					"You can still keep Linux userlands with the Termux backend, which runs them in Termux with proot. " +
						"They aren't VMs: they share the phone's kernel.",
				)
				if (BuildConfig.DEBUG) {
					Text("This is a debug build, so the demo backend is there for trying the rest of Rui.")
				}
			}
		}
		Section(title = "Permissions") {
			for (permission in Privileges.PERMISSIONS) {
				Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
					val granted = permission !in state.missing
					Icon(
						if (granted) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked,
						contentDescription = if (granted) "Granted" else "Not granted",
						tint = if (granted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
					)
					Text(permission.substringAfterLast('.'), style = MaterialTheme.typography.bodyMedium)
				}
			}
			Text(
				"MANAGE_VIRTUAL_MACHINE lets Rui ask Android for VMs, and USE_CUSTOM_VIRTUAL_MACHINE lets them boot your " +
					"own Linux instead of Android's Microdroid. Android only grants them from a computer or through Shizuku.",
				style = MaterialTheme.typography.bodySmall,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
			)
		}
		if (!state.granted) {
			Section(title = "With Shizuku") {
				when (state.shizuku) {
					ShizukuState.NotInstalled -> {
						Text("Shizuku isn't installed. It lets apps run the few commands that need adb, without a computer each time.")
						OutlinedButton(onClick = {
							context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://shizuku.rikka.app/")))
						}) { Text("About Shizuku") }
					}

					ShizukuState.NotRunning -> Text(
						"Shizuku is installed but Rui can't reach it. Start it in the Shizuku app. If it's already running, close Rui and open it again.",
					)

					ShizukuState.NeedsPermission ->
						Button(onClick = {
							grantRequested = true
							runCatching { Shizuku.requestPermission(SHIZUKU_REQUEST) }
						}) { Text("Allow Rui in Shizuku") }

					ShizukuState.Ready ->
						Button(enabled = !working, onClick = { grantRequested = true }) {
							Text(if (working) "Granting…" else "Grant with Shizuku")
						}
				}
				if (state.shizuku == ShizukuState.Ready || state.shizuku == ShizukuState.NeedsPermission) {
					Text(
						"Android closes Rui for a moment while it applies the permissions, then Rui opens here again.",
						style = MaterialTheme.typography.bodySmall,
						color = MaterialTheme.colorScheme.onSurfaceVariant,
					)
				}
				result?.let { Text(it, color = MaterialTheme.colorScheme.error) }
			}
			Section(title = "With a computer") {
				Text("Turn on USB debugging, connect the phone, and run these on the computer:")
				for (command in Privileges.adbCommands(context.packageName)) CommandBox(command)
				Text(
					"Android closes Rui when the second one is granted. Open Rui again afterwards and it checks by itself.",
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
				)
				FilledTonalButton(onClick = { state = Privileges.state(context) }) { Text("I've granted it, check again") }
			}
		}
		if (state.granted && state.avfFeature && !state.hiddenApi) {
			Section(title = "Blocked by Android") {
				Text(
					"The permissions are granted, but this Android version blocks the calls Rui uses to boot a custom " +
						"kernel. Rui can't run VMs here until it supports this version.",
				)
			}
		}
		if (state.granted && !state.avfFeature) {
			Section(title = "Granted, but no hypervisor") {
				Text(
					"Android accepted the permissions, but there's still no Virtualization Framework on this device, so " +
						"they don't do anything here.",
				)
			}
		}
	}
}

private const val SHIZUKU_REQUEST = 7
