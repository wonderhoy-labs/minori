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
	LifecycleResumeEffect(Unit) {
		installed = AiriSetup.installed(context)
		onPauseOrDispose {}
	}
	var asking by remember { mutableStateOf(false) }
	val setUp = AiriSetup.isSetUp(vm)
	Panel {
		Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
			Icon(Icons.Rounded.Apps, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
			Column(Modifier.weight(1f)) {
				Text("Apps on your launcher", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
				Text(
					when {
						!installed -> "Airi shows Linux apps as Android apps, each in a window of its own. Install it to use it here."
						setUp -> "Airi shows this machine's apps while it runs. Add them to the launcher in Airi."
						!running -> "Start the machine to set up Airi in it."
						else -> "Let Airi show this machine's apps. Airi gives you a setup command; Minori installs it here."
					},
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
				)
			}
		}
		Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
			when {
				!installed ->
					FilledTonalButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(AIRI_RELEASES))) }) {
						Text("Get Airi")
					}

				setUp -> {
					FilledTonalButton(onClick = { AiriSetup.openIntent(context)?.let(context::startActivity) }) { Text("Open Airi") }
					TextButton(enabled = running, onClick = { asking = true }) { Text("Set up again") }
				}

				else -> FilledTonalButton(enabled = running && vm.access.password != null, onClick = {
					asking = true
				}) { Text("Set up Airi") }
			}
		}
	}
	if (asking) {
		var command by remember { mutableStateOf("") }
		val token = AiriSetup.token(command)
		AlertDialog(
			onDismissRequest = { asking = false },
			title = { Text("Set up Airi in ${vm.name}") },
			text = {
				Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
					Text(
						"1. In Airi, tap Set up. It copies a command and opens Android's terminal.",
						style = MaterialTheme.typography.bodyMedium,
					)
					TextButton(onClick = { AiriSetup.openIntent(context)?.let(context::startActivity) }) {
						Icon(Icons.Rounded.Apps, contentDescription = null, modifier = Modifier.size(18.dp))
						Text("  Open Airi")
					}
					Text(
						"2. Come back and paste it here. Minori runs it in this machine instead of the terminal.",
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
			},
			confirmButton = {
				TextButton(enabled = token != null, onClick = {
					asking = false
					val guest = app.machines.guest(vm.id) ?: return@TextButton
					model.run("Setting up Airi", { job ->
						AiriSetup.install(context, app.vms, vm, guest, token!!, job)
					}) {
						app.machines.refreshForwards(vm.id)
						model.message.value = "Airi's agent runs in ${vm.name} now. Open Airi to put its apps on the launcher."
					}
				}) { Text("Install") }
			},
			dismissButton = { TextButton(onClick = { asking = false }) { Text("Cancel") } },
		)
	}
}
