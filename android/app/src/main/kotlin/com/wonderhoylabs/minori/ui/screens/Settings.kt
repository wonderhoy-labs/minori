package com.wonderhoylabs.minori.ui.screens

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
import com.wonderhoylabs.minori.BuildConfig
import com.wonderhoylabs.minori.MinoriApp
import com.wonderhoylabs.minori.backend.Availability
import com.wonderhoylabs.minori.backend.TermuxHypervisor
import com.wonderhoylabs.minori.model.BackendKind
import com.wonderhoylabs.minori.service.gib
import com.wonderhoylabs.minori.setup.Privileges
import com.wonderhoylabs.minori.store.freeBytes
import com.wonderhoylabs.minori.ui.AppModel
import com.wonderhoylabs.minori.ui.Brand
import com.wonderhoylabs.minori.ui.BrandFooter
import com.wonderhoylabs.minori.ui.Fact
import com.wonderhoylabs.minori.ui.Screen
import com.wonderhoylabs.minori.ui.Section
import com.wonderhoylabs.minori.ui.SubScreen

@Composable
fun SettingsScreen(model: AppModel) {
	val context = LocalContext.current
	val app = MinoriApp.get(context)
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
				}) { Text("Allow Minori to use Termux") }
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
				"Disks stay inside Minori, where other apps can't read them. Export a machine to keep a copy elsewhere; uninstalling Minori deletes them.",
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
