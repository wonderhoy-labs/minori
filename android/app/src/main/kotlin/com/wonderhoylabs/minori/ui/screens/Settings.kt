package com.wonderhoylabs.minori.ui.screens

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.wonderhoylabs.minori.BuildConfig
import com.wonderhoylabs.minori.MinoriApp
import com.wonderhoylabs.minori.R
import com.wonderhoylabs.minori.backend.Availability
import com.wonderhoylabs.minori.backend.TermuxHypervisor
import com.wonderhoylabs.minori.model.BackendKind
import com.wonderhoylabs.minori.service.gib
import com.wonderhoylabs.minori.setup.Privileges
import com.wonderhoylabs.minori.store.freeBytes
import com.wonderhoylabs.minori.ui.AppModel
import com.wonderhoylabs.minori.ui.Brand
import com.wonderhoylabs.minori.ui.ChildProcessNotice
import com.wonderhoylabs.minori.ui.InfoRow
import com.wonderhoylabs.minori.ui.Panel
import com.wonderhoylabs.minori.ui.Screen

@Composable
fun SettingsTab(model: AppModel) {
	val context = LocalContext.current
	val app = MinoriApp.get(context)
	var refresh by remember { mutableIntStateOf(0) }
	LifecycleResumeEffect(Unit) {
		refresh++
		onPauseOrDispose {}
	}
	val privileges = remember(refresh) { Privileges.state(context) }
	val termuxPermission = rememberTermuxPermission(model) { refresh++ }
	val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { refresh++ }
	val avf = remember(refresh) { app.machines.availability(BackendKind.AVF) }
	val termux = remember(refresh) { app.machines.availability(BackendKind.TERMUX) }
	val termuxAllowed =
		context.checkSelfPermission(TermuxHypervisor.RUN_COMMAND_PERMISSION) == PackageManager.PERMISSION_GRANTED
	val termuxInstalled =
		remember(refresh) { runCatching { context.packageManager.getPackageInfo(TermuxHypervisor.TERMUX, 0) }.isSuccess }
	val termuxOffersCommands = remember(refresh) { TermuxHypervisor.offersRunCommand(context) }
	val notificationsAllowed = remember(refresh) { com.wonderhoylabs.minori.ui.notificationsAllowed(context) }

	Column(
		Modifier.fillMaxSize().verticalScroll(
			rememberScrollState(),
		).padding(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 24.dp),
		verticalArrangement = Arrangement.spacedBy(16.dp),
	) {
		Text("Settings", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
		Panel(title = "Virtual machines") {
			Status(
				avf == Availability.Ready,
				"Android Virtualization Framework",
				when {
					avf == Availability.Ready -> "Ready"
					!privileges.avfFeature -> "This phone doesn't have it"
					!privileges.granted -> "Minori needs two permissions"
					else -> (avf as? Availability.Unavailable)?.reason ?: "Not available"
				},
			)
			if (privileges.avfFeature && avf != Availability.Ready) {
				FilledTonalButton(onClick = { model.go(Screen.Setup) }) { Text("Set up") }
			} else if (privileges.avfFeature) {
				OutlinedButton(onClick = { model.go(Screen.Setup) }) { Text("Check setup") }
			}
		}
		Panel(title = "Termux") {
			Status(termux == Availability.Ready, "Linux in Termux", (termux as? Availability.Unavailable)?.reason ?: "Ready")
			if (termuxInstalled && !termuxOffersCommands) {
				Text(
					"Uninstall it, which deletes what's in Termux, then install Termux from F-Droid. They're signed " +
						"differently, so one can't update the other.",
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
				)
				FilledTonalButton(onClick = {
					context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(TermuxHypervisor.FDROID_URL)))
				}) { Text("Termux on F-Droid") }
			} else if (termuxInstalled && !termuxAllowed) {
				FilledTonalButton(onClick = {
					termuxPermission.launch(TermuxHypervisor.RUN_COMMAND_PERMISSION)
				}) { Text("Allow Minori to use Termux") }
			} else if (termux == Availability.Ready) {
				OutlinedButton(onClick = { checkTermux(model, app) }) { Text("Check Termux") }
			}
			if (termux == Availability.Ready) ChildProcessNotice("a few Termux machines, or one with a desktop,")
		}
		TerminalPanel(model)
		Panel(title = "Notifications") {
			Status(
				notificationsAllowed,
				"While machines run",
				if (notificationsAllowed) "Minori shows one, so Android keeps it running" else "Off, so Android may stop machines",
			)
			if (!notificationsAllowed && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
				FilledTonalButton(onClick = { notifications.launch(Manifest.permission.POST_NOTIFICATIONS) }) { Text("Allow") }
			}
		}
		Panel(title = "Storage") {
			InfoRow(Icons.Rounded.Storage, "Free on this phone", freeBytes(app.storageRoot).gib())
			Text(
				"Machines and images stay in Minori's private storage, where other apps can't read them. Export a " +
					"machine to keep a copy elsewhere; uninstalling Minori deletes them.",
				style = MaterialTheme.typography.bodySmall,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
			)
		}
		Panel(title = "About") {
			Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
				Image(
					painter = painterResource(R.drawable.logo),
					contentDescription = null,
					contentScale = ContentScale.Crop,
					modifier = Modifier.size(64.dp).clip(RoundedCornerShape(18.dp)),
				)
				Column {
					Text(
						Brand.NAME,
						style = MaterialTheme.typography.titleLarge,
						fontWeight = FontWeight.SemiBold,
						color = MaterialTheme.colorScheme.primary,
					)
					Text(
						"Version ${BuildConfig.VERSION_NAME}",
						style = MaterialTheme.typography.bodyMedium,
						color = MaterialTheme.colorScheme.onSurfaceVariant,
					)
				}
			}
			OutlinedButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(Brand.REPOSITORY))) }) {
				Icon(Icons.Rounded.Code, contentDescription = null, modifier = Modifier.size(18.dp))
				Text("  Source code")
			}
		}
	}
}

/**
 * Asks for Termux's `RUN_COMMAND` permission, and says so when Android doesn't grant it. Once someone has said no
 * twice, Android stops showing the dialog and answers no at once, so Minori opens its app info page, where it's under
 * Permissions as "Run commands in Termux environment".
 */
@Composable
fun rememberTermuxPermission(model: AppModel, then: () -> Unit) = LocalContext.current.let { context ->
	rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
		then()
		if (granted) return@rememberLauncherForActivityResult
		if (!TermuxHypervisor.offersRunCommand(context)) {
			model.message.value = TermuxHypervisor.PLAY_BUILD_REASON
			return@rememberLauncherForActivityResult
		}
		model.message.value =
			"Android didn't give Minori the permission. Allow \"Run commands in Termux environment\" in Minori's " +
			"permissions."
		context.startActivity(
			Intent(
				android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
				Uri.fromParts("package", context.packageName, null),
			),
		)
	}
}

/** Asks Termux to run `true`, and says whether it did or what's stopping it. */
fun checkTermux(model: AppModel, app: MinoriApp) {
	val termux = app.machines.backend(BackendKind.TERMUX) as? TermuxHypervisor ?: return
	model.run("Checking Termux", { termux.check() }) { problem ->
		model.message.value = problem ?: "Termux runs Minori's commands. You're ready to make a Termux machine."
	}
}

@Composable
private fun Status(ok: Boolean, label: String, value: String) {
	InfoRow(
		if (ok) Icons.Rounded.CheckCircle else Icons.Rounded.Error,
		label,
		value,
	)
}
