package com.wonderhoylabs.rui.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.wonderhoylabs.rui.RuiApp
import com.wonderhoylabs.rui.backend.Availability
import com.wonderhoylabs.rui.model.BackendKind
import com.wonderhoylabs.rui.model.CpuMode
import com.wonderhoylabs.rui.model.VmConfig
import com.wonderhoylabs.rui.model.VmState
import com.wonderhoylabs.rui.ui.AppModel
import com.wonderhoylabs.rui.ui.BrandFooter
import com.wonderhoylabs.rui.ui.BrandHeader
import com.wonderhoylabs.rui.ui.Screen
import com.wonderhoylabs.rui.ui.StateChip
import com.wonderhoylabs.rui.ui.backendLabel

@Composable
fun HomeScreen(model: AppModel) {
	val context = LocalContext.current
	val app = RuiApp.get(context)
	val vms by app.vms.vms.collectAsState()
	val states by app.machines.states.collectAsState()
	val avf = remember(vms.size, states) { app.machines.availability(BackendKind.AVF) }
	val importArchive =
		rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
			if (uri == null) return@rememberLauncherForActivityResult
			model.run("Importing a VM", { job ->
				context.contentResolver.openFileDescriptor(uri, "r").use { fd ->
					app.operations.importArchive(fd!!.fd, job)
				}
			}) { vm -> model.go(Screen.Detail(vm.id)) }
		}
	Box(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
		LazyColumn(
			modifier = Modifier.fillMaxSize().widthIn(max = 840.dp).padding(horizontal = 24.dp),
			verticalArrangement = Arrangement.spacedBy(16.dp),
		) {
			item {
				Row(modifier = Modifier.fillMaxWidth().padding(top = 32.dp), verticalAlignment = Alignment.CenterVertically) {
					BrandHeader(subtitle = "Virtual machines on this phone", modifier = Modifier.weight(1f))
					IconButton(onClick = { model.go(Screen.Images) }) { Icon(Icons.Filled.Album, contentDescription = "Images") }
					IconButton(onClick = { model.go(Screen.Settings) }) { Icon(Icons.Filled.Settings, contentDescription = "Settings") }
				}
			}
			item { StatusCard(avf, onSetup = { model.go(Screen.Setup) }) }
			if (vms.isEmpty()) {
				item {
					Text(
						"No machines yet. Make one from the catalog, an image you have, or a .ruivm archive.",
						style = MaterialTheme.typography.bodyLarge,
						color = MaterialTheme.colorScheme.onSurfaceVariant,
					)
				}
			}
			items(vms, key = { it.id }) { vm ->
				VmCard(vm, states[vm.id] ?: VmState.Stopped, onOpen = { model.go(Screen.Detail(vm.id)) }, onStart = {
					app.machines.start(vm.id)
				}, onStop = { app.machines.stop(vm.id) })
			}
			item {
				TextButton(onClick = { importArchive.launch(arrayOf("*/*")) }) {
					Icon(Icons.Filled.FileOpen, contentDescription = null)
					Spacer(Modifier.padding(4.dp))
					Text("Import a .ruivm archive")
				}
			}
			item {
				BrandFooter(modifier = Modifier.padding(bottom = 88.dp))
			}
		}
		ExtendedFloatingActionButton(
			onClick = { model.go(Screen.Create()) },
			icon = { Icon(Icons.Filled.Add, contentDescription = null) },
			text = { Text("New machine") },
			modifier = Modifier.align(Alignment.BottomEnd).padding(24.dp),
		)
	}
}

@Composable
private fun StatusCard(
	avf: Availability,
	onSetup: () -> Unit,
) {
	val ready = avf == Availability.Ready
	Card(
		modifier = Modifier.fillMaxWidth().clickable(onClick = onSetup),
		colors =
			CardDefaults.cardColors(
				containerColor = if (ready) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
				contentColor = if (ready) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
			),
	) {
		Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
			Text(if (ready) "Hypervisor ready" else "Hypervisor not available", style = MaterialTheme.typography.titleMedium)
			Text(
				when (avf) {
					Availability.Ready -> "Android's Virtualization Framework is set up. Tap to check the setup."
					is Availability.Unavailable -> "${avf.reason} Tap to see what's needed."
				},
				style = MaterialTheme.typography.bodyMedium,
			)
		}
	}
}

@Composable
private fun VmCard(
	vm: VmConfig,
	state: VmState,
	onOpen: () -> Unit,
	onStart: () -> Unit,
	onStop: () -> Unit,
) {
	Card(
		modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen),
		colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
	) {
		Row(modifier = Modifier.padding(start = 20.dp, end = 12.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
			Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
				Text(vm.name, style = MaterialTheme.typography.titleMedium)
				Text(
					listOfNotNull(
						backendLabel(vm.backend),
						vm.os,
						"${vm.memoryMib / 1024.0} GiB".replace(".0 ", " "),
						if (vm.cpu == CpuMode.ONE) "1 CPU" else "All CPUs",
					).joinToString(" · "),
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
				)
				StateChip(state)
			}
			val running = state == VmState.Running || state == VmState.Starting
			FilledTonalIconButton(onClick = if (running) onStop else onStart, enabled = state != VmState.Stopping) {
				Icon(if (running) Icons.Filled.Stop else Icons.Filled.PlayArrow, contentDescription = if (running) "Stop" else "Start")
			}
		}
	}
}
