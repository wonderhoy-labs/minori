package com.wonderhoylabs.minori.ui.screens

import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.wonderhoylabs.minori.MinoriApp
import com.wonderhoylabs.minori.model.VmConfig
import com.wonderhoylabs.minori.service.AiriSetup
import com.wonderhoylabs.minori.ui.AppModel
import com.wonderhoylabs.minori.ui.Panel

private const val AIRI_RELEASES = "https://github.com/wonderhoy-labs/airi/releases/latest"

/** Puts a machine's Linux apps on the Android launcher through Airi, if it's installed. */
@Composable
fun AiriPanel(model: AppModel, vm: VmConfig, running: Boolean) {
	val context = LocalContext.current
	val app = MinoriApp.get(context)
	var installed by remember { mutableStateOf(AiriSetup.installed(context)) }
	var current by remember { mutableStateOf(AiriSetup.knowsMachines(context)) }
	LifecycleResumeEffect(Unit) {
		installed = AiriSetup.installed(context)
		current = AiriSetup.knowsMachines(context)
		onPauseOrDispose {}
	}
	// Airi handed over its token for this machine: open the dialog with it filled in.
	val handedOver = model.airiToken.value?.takeIf { it.first == vm.id }?.second
	var asking by remember { mutableStateOf(false) }
	LaunchedEffect(handedOver) { if (handedOver != null) asking = true }
	val setUp = AiriSetup.isSetUp(vm)
	Panel {
		Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
			Icon(Icons.Rounded.Apps, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
			Column(Modifier.weight(1f)) {
				Text("Apps on your launcher", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
				Text(
					when {
						!installed -> "Airi shows Linux apps as Android apps, each in a window of its own. Install it to use it here."
						!current -> "This Airi only knows Android's terminal. Update it to 0.4.0 or later to use it with this machine."
						setUp -> "Airi lists this machine's apps alongside Android's terminal's. Opening one starts the machine."
						else -> "Let Airi show this machine's apps. Tap Set up under ${vm.name} in Airi, and Minori installs it here."
					},
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
				)
			}
		}
		Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
			when {
				!installed || !current ->
					FilledTonalButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(AIRI_RELEASES))) }) {
						Text(if (installed) "Update Airi" else "Get Airi")
					}

				setUp -> {
					FilledTonalButton(onClick = { AiriSetup.openIntent(context)?.let(context::startActivity) }) { Text("Open Airi") }
					TextButton(enabled = vm.access.password != null, onClick = { asking = true }) { Text("Set up again") }
				}

				else -> {
					FilledTonalButton(onClick = { AiriSetup.openIntent(context)?.let(context::startActivity) }) { Text("Open Airi") }
					TextButton(enabled = vm.access.password != null, onClick = { asking = true }) { Text("Paste a command") }
				}
			}
		}
	}
	if (asking) {
		var command by remember { mutableStateOf(handedOver.orEmpty()) }
		val token = AiriSetup.token(command)
		val close = {
			asking = false
			model.airiToken.value = null
		}
		AlertDialog(
			onDismissRequest = close,
			title = { Text("Set up Airi in ${vm.name}") },
			text = {
				Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
					if (handedOver != null) {
						Text(
							"Airi asked to show this machine's apps. Minori installs Airi's agent in ${vm.name} over SSH" +
								if (running) "." else ", starting it first.",
							style = MaterialTheme.typography.bodyMedium,
						)
					} else {
						Text(
							"Paste the command Airi's Set up copies, or open Airi and tap Set up under ${vm.name}.",
							style = MaterialTheme.typography.bodyMedium,
						)
						TextButton(onClick = {
							val clip = context.getSystemService(ClipboardManager::class.java).primaryClip
							clip?.getItemAt(0)?.coerceToText(context)?.let { command = it.toString() }
						}) {
							Icon(Icons.Rounded.ContentPaste, contentDescription = null, modifier = Modifier.size(18.dp))
							Text("  Paste")
						}
						OutlinedTextField(
							value = command,
							onValueChange = { command = it },
							placeholder = { Text("sh …/install.sh 1f2e…") },
							isError = command.isNotBlank() && token == null,
							supportingText = { if (command.isNotBlank() && token == null) Text("That isn't Airi's setup command.") },
							shape = RoundedCornerShape(14.dp),
							modifier = Modifier.fillMaxWidth(),
						)
					}
				}
			},
			confirmButton = {
				TextButton(enabled = token != null && vm.access.password != null, onClick = {
					close()
					model.run("Setting up Airi", { job ->
						val guest = AiriSetup.startForSsh(app, vm, job)
						AiriSetup.install(context, app.vms, vm, guest, token!!, job)
					}) {
						app.machines.refreshForwards(vm.id)
						model.message.value = "Airi's agent runs in ${vm.name} now. Its apps are in Airi."
					}
				}) { Text(if (running) "Install" else "Start and install") }
			},
			dismissButton = { TextButton(onClick = close) { Text("Cancel") } },
		)
	}
}
