package com.wonderhoylabs.rui.ui.screens

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.wonderhoylabs.rui.BuildConfig
import com.wonderhoylabs.rui.RuiApp
import com.wonderhoylabs.rui.backend.Availability
import com.wonderhoylabs.rui.backend.TermuxHypervisor
import com.wonderhoylabs.rui.model.BackendKind
import com.wonderhoylabs.rui.service.gib
import com.wonderhoylabs.rui.setup.Privileges
import com.wonderhoylabs.rui.store.freeBytes
import com.wonderhoylabs.rui.ui.AppModel
import com.wonderhoylabs.rui.ui.Brand
import com.wonderhoylabs.rui.ui.BrandFooter
import com.wonderhoylabs.rui.ui.Fact
import com.wonderhoylabs.rui.ui.Screen
import com.wonderhoylabs.rui.ui.Section
import com.wonderhoylabs.rui.ui.SubScreen

@Composable
fun SettingsScreen(model: AppModel) {
	val context = LocalContext.current
	val app = RuiApp.get(context)
	var refresh by remember { mutableStateOf(0) }
	val privileges = remember(refresh) { Privileges.state(context) }
	val termuxPermission =
		rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { refresh++ }
	val notifications =
		rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { refresh++ }
	SubScreen(title = "Settings", model = model) {
		Section(title = "Hypervisor") {
			Fact("Virtualization Framework", if (privileges.avfFeature) "Present" else "Not on this device")
			Fact("Permissions", if (privileges.granted) "Granted" else "${privileges.missing.size} missing")
			Fact(
				"Shizuku",
				privileges.shizuku.name.replace(Regex("([a-z])([A-Z])"), "$1 $2").lowercase().replaceFirstChar {
					it.uppercase()
				},
			)
			FilledTonalButton(onClick = { model.go(Screen.Setup) }) { Text("Open setup") }
		}
		Section(title = "Backends") {
			for (kind in listOfNotNull(BackendKind.AVF, BackendKind.TERMUX, BackendKind.DEMO.takeIf { BuildConfig.DEBUG })) {
				val availability = remember(refresh) { app.machines.availability(kind) }
				Fact(
					when (kind) {
						BackendKind.AVF -> "AVF VMs"
						BackendKind.TERMUX -> "Termux userlands"
						BackendKind.DEMO -> "Demo shell (debug builds)"
					},
					if (availability == Availability.Ready) "Ready" else "Not available",
				)
				(availability as? Availability.Unavailable)?.let {
					Text(it.reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
				}
			}
			if (context.checkSelfPermission(TermuxHypervisor.RUN_COMMAND_PERMISSION) !=
				android.content.pm.PackageManager.PERMISSION_GRANTED
			) {
				OutlinedButton(onClick = {
					termuxPermission.launch(TermuxHypervisor.RUN_COMMAND_PERMISSION)
				}) { Text("Allow Rui to use Termux") }
			}
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
				context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
				android.content.pm.PackageManager.PERMISSION_GRANTED
			) {
				OutlinedButton(onClick = {
					notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
				}) { Text("Show a notification while machines run") }
			}
		}
		Section(title = "Storage") {
			Fact("Machines and images", "This app's private storage")
			Fact("Free", freeBytes(app.storageRoot).gib())
			Text(
				"Disks stay inside Rui, where other apps can't read them. Export a machine to keep a copy elsewhere; uninstalling Rui deletes them.",
				style = MaterialTheme.typography.bodySmall,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
			)
		}
		Section(title = "About") {
			Fact("Version", BuildConfig.VERSION_NAME)
			Text(
				"Early software. AVF VMs haven't been run on a phone yet; see the README for exactly what has been tested.",
				style = MaterialTheme.typography.bodySmall,
			)
			OutlinedButton(onClick = {
				context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(Brand.REPOSITORY)))
			}) { Text("Source and README") }
		}
		BrandFooter()
	}
}
