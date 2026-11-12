package com.wonderhoylabs.minori.ui.screens

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.automirrored.rounded.ScreenShare
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DesktopWindows
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Lan
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.VpnKey
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.wonderhoylabs.minori.MinoriApp
import com.wonderhoylabs.minori.backend.TermuxHypervisor
import com.wonderhoylabs.minori.model.BackendKind
import com.wonderhoylabs.minori.model.CpuMode
import com.wonderhoylabs.minori.model.Exposure
import com.wonderhoylabs.minori.model.PortForward
import com.wonderhoylabs.minori.model.Protocol
import com.wonderhoylabs.minori.model.VmConfig
import com.wonderhoylabs.minori.model.VmState
import com.wonderhoylabs.minori.net.Firewall
import com.wonderhoylabs.minori.net.NetworkState
import com.wonderhoylabs.minori.service.AiriSetup
import com.wonderhoylabs.minori.service.Catalog
import com.wonderhoylabs.minori.service.TermuxDesktop
import com.wonderhoylabs.minori.service.gib
import com.wonderhoylabs.minori.store.usage
import com.wonderhoylabs.minori.ui.ActionTile
import com.wonderhoylabs.minori.ui.AppModel
import com.wonderhoylabs.minori.ui.ConfirmDialog
import com.wonderhoylabs.minori.ui.InfoRow
import com.wonderhoylabs.minori.ui.MachineTile
import com.wonderhoylabs.minori.ui.Panel
import com.wonderhoylabs.minori.ui.Screen
import com.wonderhoylabs.minori.ui.StatusPill
import com.wonderhoylabs.minori.ui.SubScreen
import com.wonderhoylabs.minori.ui.backendLabel
import com.wonderhoylabs.minori.ui.copy
import com.wonderhoylabs.minori.ui.rememberStartAsking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@Composable
fun DetailScreen(model: AppModel, vmId: String) {
	val context = LocalContext.current
	val app = MinoriApp.get(context)
	val vms by app.vms.vms.collectAsState()
	val vm = vms.firstOrNull { it.id == vmId }
	if (vm == null) {
		LaunchedEffect(Unit) { model.back() }
		return
	}
	val states by app.machines.states.collectAsState()
	val state = states[vm.id] ?: VmState.Stopped
	val running = state == VmState.Running
	val template = Catalog.template(vm.template)
	val desktop = template?.vncPort != null || vm.access.vncPassword != null
	var menu by remember { mutableStateOf(false) }
	var confirmDelete by remember { mutableStateOf(false) }
	var confirmForce by remember { mutableStateOf(false) }
	var editPort by remember { mutableStateOf<PortForward?>(null) }
	var exportAsk by remember { mutableStateOf(false) }
	var exportSecrets by remember { mutableStateOf(false) }
	// The ports the guest listens on, asked of its agent every few seconds while it runs.
	var listening by remember { mutableStateOf<List<Int>?>(null) }
	LaunchedEffect(vm.id, running) {
		listening = null
		while (running) {
			listening = withContext(Dispatchers.IO) { runCatching { app.machines.guest(vm.id)?.listeningPorts() }.getOrNull() }
			delay(4000)
		}
	}
	val exporter =
		rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
			if (uri == null) return@rememberLauncherForActivityResult
			model.run("Exporting ${vm.name}", { job ->
				try {
					context.contentResolver.openFileDescriptor(uri, "wt").use { fd ->
						app.operations.export(vm, fd!!.fd, exportSecrets, job)
					}
				} catch (error: Throwable) {
					// Don't leave a half-written archive where the user will find it.
					runCatching { android.provider.DocumentsContract.deleteDocument(context.contentResolver, uri) }
					throw error
				}
			}) { model.message.value = "Exported ${vm.name}." }
		}

	SubScreen(title = "", model = model, actions = {
		Box {
			IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, contentDescription = "More") }
			DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
				DropdownMenuItem(text = { Text("Edit") }, onClick = {
					menu = false
					model.go(Screen.Edit(vm.id))
				})
				DropdownMenuItem(text = { Text("Clone") }, enabled = !running, onClick = {
					menu = false
					model.run("Copying ${vm.name}", { job -> app.operations.clone(vm, job) }) { model.replace(Screen.Detail(it.id)) }
				})
				if (vm.backend != BackendKind.TERMUX) {
					DropdownMenuItem(text = { Text("Export") }, enabled = !running, onClick = {
						menu = false
						exportAsk = true
					})
				}
				DropdownMenuItem(text = { Text("Delete") }, enabled = !running, onClick = {
					menu = false
					confirmDelete = true
				})
			}
		}
	}) {
		val start = rememberStartAsking { app.machines.start(it) }
		Hero(vm, state, onStart = { start(vm.id) }, onStop = { app.machines.stop(vm.id) }, onForce = {
			confirmForce =
				true
		})
		Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
			ActionTile(Icons.Rounded.Terminal, "Console", running) { model.go(Screen.Console(vm.id)) }
			if (vm.backend != BackendKind.TERMUX) {
				ActionTile(Icons.Rounded.VpnKey, "SSH", running && vm.access.password != null) { model.go(Screen.Ssh(vm.id)) }
			}
			if (desktop) {
				ActionTile(Icons.Rounded.DesktopWindows, "Desktop", running) { model.go(Screen.Vnc(vm.id, vm.access.vncPort)) }
			}
		}
		if (running && desktop && template != null) {
			val ready = listening?.contains(vm.access.vncPort) == true
			AnimatedVisibility(visible = !ready) {
				Panel {
					Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
						CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 3.dp)
						Column(Modifier.weight(1f)) {
							Text(
								"Setting up ${template.name}",
								style = MaterialTheme.typography.titleSmall,
								fontWeight = FontWeight.SemiBold,
							)
							Text(
								"The first boot downloads and installs it, which takes ${template.firstBoot}. The " +
									"desktop opens once it's ready; the console shows the progress.",
								style = MaterialTheme.typography.bodySmall,
								color = MaterialTheme.colorScheme.onSurfaceVariant,
							)
						}
					}
				}
			}
		}
		(state as? VmState.Failed)?.let {
			Panel {
				Text(it.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
			}
		}
		if (vm.backend != BackendKind.TERMUX) InternetPanel(vm, running)
		if (vm.access.password != null) LoginPanel(model, vm, running, desktop)
		if (AiriSetup.canSetUp(vm)) AiriPanel(model, vm, running)
		if (TermuxDesktop.canSetUp(vm) && !TermuxDesktop.isSetUp(vm)) TermuxDesktopPanel(model, vm, running)
		if (vm.backend != BackendKind.TERMUX) PortsPanel(vm, running, listening, onEdit = { editPort = it })
		Panel(title = "Machine") {
			InfoRow(Icons.Rounded.Memory, "Runs as", backendLabel(vm.backend))
			if (vm.backend == BackendKind.TERMUX) {
				val distro = TermuxHypervisor.DISTROS.firstOrNull { it.id == vm.distro }?.name ?: vm.distro ?: "Linux"
				InfoRow(Icons.Rounded.Storage, "Distribution", "$distro, with proot")
			}
			if (vm.backend != BackendKind.TERMUX) {
				InfoRow(
					Icons.Rounded.Memory,
					"Size",
					"${memoryLabel(vm.memoryMib)} memory · " + if (vm.cpu == CpuMode.ONE) "one processor" else "all processors",
				)
				for (disk in vm.disks) {
					val usage = app.vms.diskFile(vm, disk.file).usage()
					InfoRow(Icons.Rounded.Storage, disk.label, "${usage.used.gib()} used of ${usage.allocated.gib()}")
				}
				if (vm.boot == null && vm.backend == BackendKind.AVF) {
					Text(
						"No kernel chosen yet, so it can't start. Edit it and pick a boot disk.",
						color = MaterialTheme.colorScheme.error,
						style = MaterialTheme.typography.bodySmall,
					)
				}
			}
			TextButton(onClick = { model.go(Screen.Edit(vm.id)) }) {
				Icon(Icons.Rounded.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
				Text("  Change")
			}
		}
		val log by app.machines.log(vm.id).collectAsState()
		if (log.isNotEmpty()) {
			Panel(title = "Kernel log") {
				Text(log.takeLast(3000), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
			}
		}
		if (vm.notes.isNotBlank()) Panel(title = "Notes") { Text(vm.notes) }
	}

	if (exportAsk) {
		ConfirmDialog(
			title = "Export ${vm.name}",
			text = "Saves its settings and disks in one .minorivm file, to move it to another phone or keep a copy.",
			confirm = "Choose where",
			onConfirm = { exporter.launch("${vm.name}.minorivm") },
			onDismiss = { exportAsk = false },
			extra = {
				Row(
					verticalAlignment = Alignment.CenterVertically,
					modifier = Modifier.toggleable(exportSecrets, role = Role.Checkbox) { exportSecrets = it },
				) {
					Checkbox(checked = exportSecrets, onCheckedChange = null)
					Text("  Include its password")
				}
			},
		)
	}
	if (confirmForce) {
		ConfirmDialog(
			title = "Force stop ${vm.name}?",
			text = "It stops at once, like pulling the plug. Anything it hasn't written to disk is lost.",
			confirm = "Force stop",
			onConfirm = { app.machines.forceStop(vm.id) },
			onDismiss = { confirmForce = false },
		)
	}
	if (confirmDelete) {
		var keepDisks by remember { mutableStateOf(false) }
		ConfirmDialog(
			title = "Delete ${vm.name}?",
			text =
				if (keepDisks) {
					"Its settings go, and its disks move to Images."
				} else {
					"Its settings and disks are deleted. This can't be undone."
				},
			confirm = "Delete",
			onConfirm = {
				app.machines.forceStop(vm.id)
				app.vms.delete(vm.id, keepDisks, app.images)
				model.back()
			},
			onDismiss = { confirmDelete = false },
			extra = {
				if (vm.backend != BackendKind.TERMUX) {
					Row(
						verticalAlignment = Alignment.CenterVertically,
						modifier = Modifier.toggleable(keepDisks, role = Role.Checkbox) { keepDisks = it },
					) {
						Checkbox(checked = keepDisks, onCheckedChange = null)
						Text("  Keep its disks in Images")
					}
				}
			},
		)
	}
	val localNetwork = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
		app.machines.refreshForwards(vm.id)
		if (!granted) {
			model.message.value =
				"Without access to your local network, Android keeps other devices out. Allow it in Minori's permissions."
		}
	}
	editPort?.let { forward ->
		PortDialog(
			initial = forward,
			isNew = vm.network.forwards.none { it.id == forward.id },
			onDismiss = { editPort = null },
			onSave = { saved ->
				app.vms.update(vm.id) { current ->
					val others = current.network.forwards.filterNot { it.id == saved.id }
					current.copy(network = current.network.copy(forwards = others + saved))
				}
				app.machines.refreshForwards(vm.id)
				editPort = null
				if (saved.exposure == Exposure.NETWORK && !localNetworkAllowed(context)) {
					localNetwork.launch(LOCAL_NETWORK)
				}
			},
			onDelete = {
				app.vms.update(vm.id) { current ->
					val kept = current.network.forwards.filterNot { it.id == forward.id }
					current.copy(network = current.network.copy(forwards = kept))
				}
				app.machines.refreshForwards(vm.id)
				editPort = null
			},
		)
	}
}

@Composable
private fun Hero(vm: VmConfig, state: VmState, onStart: () -> Unit, onStop: () -> Unit, onForce: () -> Unit) {
	val scheme = MaterialTheme.colorScheme
	val running = state == VmState.Running || state == VmState.Starting
	// One flat colour that warms up while the machine runs.
	val container by animateColorAsState(
		if (running) scheme.primaryContainer else scheme.surfaceContainerHigh,
		label = "hero",
	)
	Card(
		shape = RoundedCornerShape(28.dp),
		colors = CardDefaults.cardColors(containerColor = container),
		modifier = Modifier.fillMaxWidth(),
	) {
		Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
			Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
				MachineTile(vm, size = 64.dp)
				Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
					Text(vm.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
					StatusPill(state)
				}
			}
			Text(
				machineSummary(vm),
				style = MaterialTheme.typography.bodyMedium,
				color = scheme.onSurfaceVariant,
			)
			Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
				when (state) {
					VmState.Running, VmState.Starting -> {
						Button(onClick = onStop, modifier = Modifier.weight(1f)) {
							Icon(Icons.Rounded.PowerSettingsNew, contentDescription = null, modifier = Modifier.size(18.dp))
							Text("  Shut down")
						}
						OutlinedButton(onClick = onForce) { Text("Force stop") }
					}

					VmState.Stopping -> OutlinedButton(onClick = onForce, modifier = Modifier.weight(1f)) { Text("Force stop") }

					else ->
						Button(onClick = onStart, modifier = Modifier.weight(1f)) {
							Icon(Icons.Rounded.PowerSettingsNew, contentDescription = null, modifier = Modifier.size(18.dp))
							Text("  Start")
						}
				}
			}
		}
	}
}

@Composable
private fun InternetPanel(vm: VmConfig, running: Boolean) {
	val app = MinoriApp.get(LocalContext.current)
	app.machines.networkRevision.collectAsState().value
	val network = app.machines.network(vm.id)
	val state = network?.state?.collectAsState()?.value
	Panel {
		Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
			Icon(Icons.Rounded.Public, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
			Column(Modifier.weight(1f)) {
				Text("Internet", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
				Text(
					when {
						!vm.agent -> "Needs Minori's guest agent, which machines from the store and first-boot setup have."
						!vm.network.internet -> "Off. The guest can't reach the internet."
						!running -> "Through the phone's connection while it runs."
						state == NetworkState.Connected -> "Connected through the phone."
						else -> "Waiting for the guest's agent…"
					},
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
				)
			}
			Switch(
				checked = vm.network.internet && vm.agent,
				enabled = vm.agent,
				onCheckedChange = { on -> app.machines.setInternet(vm.id, on) },
			)
		}
	}
}

@Composable
private fun LoginPanel(model: AppModel, vm: VmConfig, running: Boolean, desktop: Boolean) {
	val context = LocalContext.current
	val app = MinoriApp.get(context)
	Panel(title = "Login") {
		InfoRow(Icons.Rounded.Person, "User", vm.access.user)
		vm.access.password?.let { password ->
			InfoRow(Icons.Rounded.VpnKey, "Password", "••••••••••") {
				IconButton(onClick = {
					copy(context, password)
				}) { Icon(Icons.Rounded.ContentCopy, contentDescription = "Copy password") }
			}
		}
		if (vm.backend == BackendKind.TERMUX && desktop) {
			// A Termux machine's Remote Desktop is already on the phone's loopback, on the machine's own port.
			FilledTonalButton(enabled = running, onClick = {
				openExternal(
					context,
					Uri.parse("rdp://full%20address=s:127.0.0.1:${vm.access.rdpPort}&username=s:${vm.access.user}"),
					"a Remote Desktop app such as Windows App or aFreeRDP",
					"127.0.0.1:${vm.access.rdpPort}",
					model,
				)
			}) {
				Icon(Icons.AutoMirrored.Rounded.ScreenShare, contentDescription = null, modifier = Modifier.size(18.dp))
				Text("  RDP app")
			}
		}
		if (vm.backend != BackendKind.TERMUX) {
			Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
				FilledTonalButton(enabled = running, onClick = {
					val guest = app.machines.guest(vm.id) ?: return@FilledTonalButton
					model.run("Opening SSH", { app.machines.forwarder.temporary(vm.id, vm.access.sshPort, guest) }) { port ->
						openExternal(
							context,
							Uri.parse("ssh://${vm.access.user}@127.0.0.1:$port"),
							"an SSH app",
							"ssh -p $port ${vm.access.user}@127.0.0.1",
							model,
						)
					}
				}) {
					Icon(Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
					Text("  SSH app")
				}
				if (desktop) {
					FilledTonalButton(enabled = running, onClick = {
						val guest = app.machines.guest(vm.id) ?: return@FilledTonalButton
						model.run("Opening Remote Desktop", {
							app.machines.forwarder.temporary(vm.id, vm.access.rdpPort, guest)
						}) { port ->
							openExternal(
								context,
								Uri.parse("rdp://full%20address=s:127.0.0.1:$port&username=s:${vm.access.user}"),
								"a Remote Desktop app such as Windows App or aFreeRDP",
								"127.0.0.1:$port",
								model,
							)
						}
					}) {
						Icon(Icons.AutoMirrored.Rounded.ScreenShare, contentDescription = null, modifier = Modifier.size(18.dp))
						Text("  RDP app")
					}
				}
			}
		}
	}
}

@Composable
private fun PortsPanel(vm: VmConfig, running: Boolean, listening: List<Int>?, onEdit: (PortForward) -> Unit) {
	val context = LocalContext.current
	val app = MinoriApp.get(context)
	val forwards by app.machines.forwarder.status.collectAsState()
	Panel(title = "Ports", action = {
		IconButton(onClick = { onEdit(PortForward(hostPort = 2222, guestPort = 22, label = "SSH")) }) {
			Icon(Icons.Rounded.Add, contentDescription = "Publish a port")
		}
	}) {
		if (vm.network.forwards.isEmpty()) {
			Text(
				"Nothing in the guest can be reached from the phone or your network until you publish a port.",
				style = MaterialTheme.typography.bodySmall,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
			)
		}
		for (forward in vm.network.forwards) {
			val status = forwards[forward.id]
			InfoRow(
				Icons.Rounded.Lan,
				"${forward.label.ifEmpty { "Port" }} · ${forward.protocol.name}",
				"${if (forward.exposure == Exposure.LOOPBACK) "This phone" else "Network"} :${forward.hostPort} → guest :${forward.guestPort}",
			) {
				IconButton(onClick = { onEdit(forward) }) { Icon(Icons.Rounded.Edit, contentDescription = "Edit port") }
			}
			Text(
				when {
					!forward.enabled -> "Off"

					forward.exposure == Exposure.NETWORK && !localNetworkAllowed(context) ->
						"Only this phone can connect: Minori needs access to your local network, which Android asks for when you save the port"

					status == null -> if (running) "Not listening" else "Listens while the machine runs"

					status.error != null -> status.error

					else -> "${status.open} open · ${status.served} served · ${status.refused} refused"
				},
				style = MaterialTheme.typography.labelMedium,
				color = if (status?.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
				modifier = Modifier.padding(start = 38.dp),
			)
		}
		val unpublished = listening.orEmpty().filter { port -> vm.network.forwards.none { it.guestPort == port } }
		if (running && unpublished.isNotEmpty()) {
			Text(
				"Listening in the guest: ${unpublished.joinToString(", ")}",
				style = MaterialTheme.typography.bodySmall,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
			)
		}
	}
}

private fun openExternal(context: Context, uri: Uri, what: String, fallback: String, model: AppModel) {
	try {
		context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
	} catch (_: ActivityNotFoundException) {
		copy(context, fallback)
		model.message.value = "No app here opens it. Install $what. Minori copied the address: $fallback"
	}
}

@Composable
fun PortDialog(
	initial: PortForward,
	isNew: Boolean,
	onDismiss: () -> Unit,
	onSave: (PortForward) -> Unit,
	onDelete: () -> Unit,
) {
	var label by remember { mutableStateOf(initial.label) }
	var protocol by remember { mutableStateOf(initial.protocol) }
	var host by remember { mutableStateOf(initial.hostPort.toString()) }
	var guest by remember { mutableStateOf(initial.guestPort.toString()) }
	var exposure by remember { mutableStateOf(initial.exposure) }
	var allow by remember { mutableStateOf(initial.allow.joinToString(", ")) }
	var deny by remember { mutableStateOf(initial.deny.joinToString(", ")) }
	var enabled by remember { mutableStateOf(initial.enabled) }

	fun list(text: String) = text.split(',', ' ', '\n').map { it.trim() }.filter { it.isNotEmpty() }
	val badAllow = Firewall.invalid(list(allow))
	val badDeny = Firewall.invalid(list(deny))
	val hostPort = host.toIntOrNull()
	val guestPort = guest.toIntOrNull()
	val valid = hostPort in 1024..65535 && guestPort in 1..65535 && badAllow.isEmpty() && badDeny.isEmpty()
	AlertDialog(
		onDismissRequest = onDismiss,
		title = { Text(if (isNew) "Publish a port" else "Published port") },
		text = {
			Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
				OutlinedTextField(value = label, onValueChange = { label = it }, label = { Text("Name") }, singleLine = true)
				SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
					Protocol.entries.forEachIndexed { index, option ->
						SegmentedButton(selected = protocol == option, onClick = {
							protocol = option
						}, shape = SegmentedButtonDefaults.itemShape(index, 2)) {
							Text(option.name)
						}
					}
				}
				Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
					OutlinedTextField(
						value = host,
						onValueChange = { host = it.filter(Char::isDigit) },
						label = { Text("Phone port") },
						singleLine = true,
						isError = hostPort !in 1024..65535,
						keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
						modifier = Modifier.weight(1f),
					)
					OutlinedTextField(
						value = guest,
						onValueChange = { guest = it.filter(Char::isDigit) },
						label = { Text("Guest port") },
						singleLine = true,
						keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
						modifier = Modifier.weight(1f),
					)
				}
				SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
					SegmentedButton(
						selected = exposure == Exposure.LOOPBACK,
						onClick = { exposure = Exposure.LOOPBACK },
						shape = SegmentedButtonDefaults.itemShape(0, 2),
					) { Text("This phone") }
					SegmentedButton(
						selected = exposure == Exposure.NETWORK,
						onClick = { exposure = Exposure.NETWORK },
						shape = SegmentedButtonDefaults.itemShape(1, 2),
					) { Text("Network") }
				}
				if (exposure == Exposure.NETWORK) {
					Text(
						"Other devices on the phone's networks can connect. Limit who with the lists below.",
						style = MaterialTheme.typography.bodySmall,
					)
				}
				OutlinedTextField(
					value = allow,
					onValueChange = { allow = it },
					label = { Text("Allow only") },
					placeholder = { Text("192.168.1.0/24") },
					isError = badAllow.isNotEmpty(),
					supportingText = {
						val hint = "Empty allows everyone who can reach the port"
						Text(if (badAllow.isNotEmpty()) "Not an address: ${badAllow.joinToString()}" else hint)
					},
				)
				OutlinedTextField(
					value = deny,
					onValueChange = { deny = it },
					label = { Text("Always refuse") },
					isError = badDeny.isNotEmpty(),
					supportingText = { if (badDeny.isNotEmpty()) Text("Not an address: ${badDeny.joinToString()}") },
				)
				Row(verticalAlignment = Alignment.CenterVertically) {
					Text("On", modifier = Modifier.weight(1f))
					Switch(checked = enabled, onCheckedChange = { enabled = it })
				}
			}
		},
		confirmButton = {
			TextButton(enabled = valid, onClick = {
				onSave(
					initial.copy(
						label = label,
						protocol = protocol,
						hostPort = hostPort ?: 0,
						guestPort = guestPort ?: 0,
						exposure = exposure,
						allow = list(allow),
						deny = list(deny),
						enabled = enabled,
					),
				)
			}) { Text("Save") }
		},
		dismissButton = {
			Row {
				if (!isNew) TextButton(onClick = onDelete) { Text("Remove") }
				TextButton(onClick = onDismiss) { Text("Cancel") }
			}
		},
	)
}

/** For a Termux machine without a desktop: adds Xfce, which shows here, in Asahi and in Remote Desktop apps. */
@Composable
private fun TermuxDesktopPanel(model: AppModel, vm: VmConfig, running: Boolean) {
	val context = LocalContext.current
	val app = MinoriApp.get(context)
	Panel(title = "Desktop") {
		Text(
			"Add Xfce to ${vm.name}. It shows in Minori and in Asahi, and Remote Desktop apps on this phone reach it too. " +
				"It downloads about 500 MB and takes a few minutes.",
			style = MaterialTheme.typography.bodySmall,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
		)
		FilledTonalButton(enabled = running, onClick = {
			model.run("Adding a desktop to ${vm.name}", { job -> TermuxDesktop.install(context, app.vms, vm, job) }) {
				model.message.value = "${vm.name} has a desktop now. Open it with Desktop."
			}
		}) { Text(if (running) "Add a desktop" else "Start it to add a desktop") }
	}
}

/** Android 17's permission for traffic with other devices on the local network, which ports on the network need. */
private const val LOCAL_NETWORK = "android.permission.ACCESS_LOCAL_NETWORK"

/** Whether other devices can reach Minori's ports: always before Android 17, which added the permission. */
private fun localNetworkAllowed(context: Context): Boolean =
	Build.VERSION.SDK_INT < 37 || context.checkSelfPermission(LOCAL_NETWORK) == PackageManager.PERMISSION_GRANTED
