package com.wonderhoylabs.minori.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.DesktopWindows
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.FileOpen
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.VpnKey
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wonderhoylabs.minori.MinoriApp
import com.wonderhoylabs.minori.R
import com.wonderhoylabs.minori.backend.Availability
import com.wonderhoylabs.minori.model.BackendKind
import com.wonderhoylabs.minori.model.CpuMode
import com.wonderhoylabs.minori.model.VmConfig
import com.wonderhoylabs.minori.model.VmState
import com.wonderhoylabs.minori.service.Catalog
import com.wonderhoylabs.minori.setup.Privileges
import com.wonderhoylabs.minori.ui.AppModel
import com.wonderhoylabs.minori.ui.Brand
import com.wonderhoylabs.minori.ui.EmptyState
import com.wonderhoylabs.minori.ui.MachineTile
import com.wonderhoylabs.minori.ui.Screen
import com.wonderhoylabs.minori.ui.StatusPill
import com.wonderhoylabs.minori.ui.Tab
import com.wonderhoylabs.minori.ui.backendLabel
import com.wonderhoylabs.minori.ui.rememberStartAsking

@Composable
fun MachinesTab(model: AppModel) {
	val context = LocalContext.current
	val app = MinoriApp.get(context)
	val start = rememberStartAsking { app.machines.start(it) }
	val vms by app.vms.vms.collectAsState()
	val states by app.machines.states.collectAsState()
	val avf = remember(vms.size, states) { app.machines.availability(BackendKind.AVF) }
	val running = states.values.count { it == VmState.Running }
	var menu by remember { mutableStateOf(false) }
	val importArchive =
		rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
			if (uri == null) return@rememberLauncherForActivityResult
			model.run("Importing a machine", { job ->
				context.contentResolver.openFileDescriptor(uri, "r").use { fd ->
					app.operations.importArchive(fd!!.fd, fd.statSize, job)
				}
			}) { vm -> model.go(Screen.Detail(vm.id)) }
		}
	Box(Modifier.fillMaxSize()) {
		LazyColumn(
			modifier = Modifier.fillMaxSize(),
			contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 104.dp),
			verticalArrangement = Arrangement.spacedBy(14.dp),
		) {
			item {
				Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
					Image(
						painterResource(R.drawable.logo),
						contentDescription = null,
						modifier = Modifier.size(52.dp).clip(RoundedCornerShape(16.dp)),
					)
					Column(Modifier.weight(1f)) {
						Text(
							Brand.NAME,
							style = MaterialTheme.typography.headlineMedium,
							fontWeight = FontWeight.SemiBold,
							color = MaterialTheme.colorScheme.primary,
						)
						Text(
							when {
								vms.isEmpty() -> Brand.TAGLINE
								running == 0 -> "${vms.size} ${if (vms.size == 1) "machine" else "machines"}, all stopped"
								else -> "$running running of ${vms.size}"
							},
							style = MaterialTheme.typography.bodyMedium,
							color = MaterialTheme.colorScheme.onSurfaceVariant,
						)
					}
					Box {
						IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, contentDescription = "More") }
						DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
							DropdownMenuItem(
								text = { Text("Import a machine") },
								leadingIcon = { Icon(Icons.Rounded.FileOpen, contentDescription = null) },
								onClick = {
									menu = false
									importArchive.launch(arrayOf("*/*"))
								},
							)
						}
					}
				}
			}
			if (avf is Availability.Unavailable) {
				val noAvf = !Privileges.state(context).avfFeature
				item {
					SetupBanner(
						title = if (noAvf) "No virtual machines on this phone" else "Finish setting up",
						reason = if (noAvf) "${avf.reason} Linux in Termux still works." else avf.reason,
					) { if (noAvf) model.tab(Tab.Store) else model.go(Screen.Setup) }
				}
			}
			if (vms.isEmpty()) {
				item {
					EmptyState(
						icon = Icons.Rounded.Dns,
						title = "No machines yet",
						text = "Pick Debian, Ubuntu, a desktop or a browser from the store, or bring your own image.",
					) {
						Button(onClick = { model.tab(Tab.Store) }, modifier = Modifier.padding(top = 8.dp)) { Text("Open the store") }
					}
				}
			}
			items(vms, key = { it.id }) { vm ->
				MachineCard(
					vm = vm,
					state = states[vm.id] ?: VmState.Stopped,
					onOpen = { model.go(Screen.Detail(vm.id)) },
					onToggle = { running -> if (running) app.machines.stop(vm.id) else start(vm.id) },
					onConsole = { model.go(Screen.Console(vm.id)) },
					onSsh = { model.go(Screen.Ssh(vm.id)) },
					onDesktop = { model.go(Screen.Vnc(vm.id, vm.access.vncPort)) },
				)
			}
		}
		if (vms.isNotEmpty()) {
			ExtendedFloatingActionButton(
				onClick = { model.tab(Tab.Store) },
				icon = { Icon(Icons.Rounded.Add, contentDescription = null) },
				text = { Text("New machine") },
				modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp),
			)
		}
	}
}

@Composable
private fun SetupBanner(title: String, reason: String, onClick: () -> Unit) {
	Card(
		onClick = onClick,
		shape = RoundedCornerShape(24.dp),
		colors =
			CardDefaults.cardColors(
				containerColor = MaterialTheme.colorScheme.secondaryContainer,
				contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
			),
	) {
		Row(
			Modifier.padding(18.dp),
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.spacedBy(14.dp),
		) {
			Icon(Icons.Rounded.WarningAmber, contentDescription = null)
			Column(Modifier.weight(1f)) {
				Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
				Text(reason, style = MaterialTheme.typography.bodySmall)
			}
			Icon(Icons.Rounded.ChevronRight, contentDescription = null)
		}
	}
}

/** What a machine is, in a few words: its OS, memory and CPUs. */
fun machineSummary(vm: VmConfig): String = listOfNotNull(
	Catalog.template(vm.template)?.name ?: vm.os?.substringBefore(" GNU/Linux")?.substringBefore(" (")?.trim(),
	if (vm.backend == BackendKind.TERMUX) null else memoryLabel(vm.memoryMib),
	if (vm.backend == BackendKind.TERMUX) {
		null
	} else if (vm.cpu == CpuMode.ONE) {
		"1 CPU"
	} else {
		"All CPUs"
	},
	if (vm.backend != BackendKind.AVF) backendLabel(vm.backend) else null,
).joinToString(" · ")

fun memoryLabel(mib: Int): String = if (mib % 1024 == 0) "${mib / 1024} GB" else "%.1f GB".format(mib / 1024.0)

@Composable
private fun MachineCard(
	vm: VmConfig,
	state: VmState,
	onOpen: () -> Unit,
	onToggle: (running: Boolean) -> Unit,
	onConsole: () -> Unit,
	onSsh: () -> Unit,
	onDesktop: () -> Unit,
) {
	val running = state == VmState.Running
	val busy = state == VmState.Starting || state == VmState.Stopping
	Card(
		onClick = onOpen,
		shape = RoundedCornerShape(24.dp),
		colors =
			CardDefaults.cardColors(
				containerColor =
					if (running) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surfaceContainerLow,
			),
		modifier = Modifier.fillMaxWidth().animateContentSize(),
	) {
		Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
			Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
				MachineTile(vm)
				Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
					Text(
						vm.name,
						style = MaterialTheme.typography.titleMedium,
						fontWeight = FontWeight.SemiBold,
						maxLines = 1,
						overflow = TextOverflow.Ellipsis,
					)
					Text(
						machineSummary(vm),
						style = MaterialTheme.typography.bodySmall,
						color = MaterialTheme.colorScheme.onSurfaceVariant,
						maxLines = 1,
						overflow = TextOverflow.Ellipsis,
					)
					StatusPill(state)
				}
				if (running || busy) {
					FilledTonalIconButton(onClick = { onToggle(true) }, enabled = !busy, modifier = Modifier.size(52.dp)) {
						Icon(Icons.Rounded.Stop, contentDescription = "Stop")
					}
				} else {
					FilledIconButton(onClick = { onToggle(false) }, modifier = Modifier.size(52.dp)) {
						Icon(Icons.Rounded.PlayArrow, contentDescription = "Start")
					}
				}
			}
			AnimatedVisibility(visible = running) {
				Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
					QuickChip("Console", Icons.Rounded.Terminal, onConsole)
					if (vm.backend != BackendKind.TERMUX) QuickChip("SSH", Icons.Rounded.VpnKey, onSsh)
					if (Catalog.template(vm.template)?.vncPort != null) QuickChip("Desktop", Icons.Rounded.DesktopWindows, onDesktop)
				}
			}
		}
	}
}

@Composable
private fun QuickChip(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
	AssistChip(
		onClick = onClick,
		label = { Text(label) },
		leadingIcon = { Icon(icon, contentDescription = null, modifier = Modifier.size(AssistChipDefaults.IconSize)) },
		shape = RoundedCornerShape(12.dp),
	)
}
