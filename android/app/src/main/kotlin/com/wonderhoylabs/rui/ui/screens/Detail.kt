package com.wonderhoylabs.rui.ui.screens

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.wonderhoylabs.rui.RuiApp
import com.wonderhoylabs.rui.model.BackendKind
import com.wonderhoylabs.rui.model.CpuMode
import com.wonderhoylabs.rui.model.DiskRole
import com.wonderhoylabs.rui.model.Exposure
import com.wonderhoylabs.rui.model.PortForward
import com.wonderhoylabs.rui.model.Protocol
import com.wonderhoylabs.rui.model.VmState
import com.wonderhoylabs.rui.net.Firewall
import com.wonderhoylabs.rui.service.gib
import com.wonderhoylabs.rui.store.usage
import com.wonderhoylabs.rui.ui.AppModel
import com.wonderhoylabs.rui.ui.ConfirmDialog
import com.wonderhoylabs.rui.ui.Fact
import com.wonderhoylabs.rui.ui.Screen
import com.wonderhoylabs.rui.ui.Section
import com.wonderhoylabs.rui.ui.StateChip
import com.wonderhoylabs.rui.ui.SubScreen
import com.wonderhoylabs.rui.ui.backendLabel
import com.wonderhoylabs.rui.ui.copy

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DetailScreen(
	model: AppModel,
	vmId: String,
) {
	val context = LocalContext.current
	val app = RuiApp.get(context)
	val vms by app.vms.vms.collectAsState()
	val vm = vms.firstOrNull { it.id == vmId }
	if (vm == null) {
		model.back()
		return
	}
	val states by app.machines.states.collectAsState()
	val state = states[vm.id] ?: VmState.Stopped
	val forwards by app.machines.forwarder.status.collectAsState()
	val running = state == VmState.Running
	var confirmDelete by remember { mutableStateOf(false) }
	var keepDisks by remember { mutableStateOf(false) }
	var editPort by remember { mutableStateOf<PortForward?>(null) }
	var confirmForce by remember { mutableStateOf(false) }
	var exportSecrets by remember { mutableStateOf(false) }
	val exporter =
		rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
			if (uri == null) return@rememberLauncherForActivityResult
			model.run("Exporting ${vm.name}", { job ->
				try {
					context.contentResolver.openFileDescriptor(uri, "wt").use { fd -> app.operations.export(vm, fd!!.fd, exportSecrets, job) }
				} catch (error: Throwable) {
					// Don't leave a half-written archive where the user will find it.
					runCatching { android.provider.DocumentsContract.deleteDocument(context.contentResolver, uri) }
					throw error
				}
			}) { model.message.value = "Exported ${vm.name}." }
		}

	SubScreen(title = vm.name, model = model, actions = {
		IconButton(onClick = { model.go(Screen.Edit(vm.id)) }) { Icon(Icons.Filled.Edit, contentDescription = "Edit") }
	}) {
		Section(title = backendLabel(vm.backend), highlight = running) {
			Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
				StateChip(state)
				vm.os?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
			}
			(state as? VmState.Failed)?.let { Text(it.message, color = MaterialTheme.colorScheme.error) }
			if (vm.backend == BackendKind.DEMO) {
				Text("This is the demo backend: a shell on this phone that stands in for a VM.", style = MaterialTheme.typography.bodySmall)
			}
			if (vm.backend == BackendKind.TERMUX) {
				Text("A userland in Termux with proot. It shares the phone's kernel; it isn't a VM.", style = MaterialTheme.typography.bodySmall)
			}
			FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
				when (state) {
					VmState.Running, VmState.Starting -> {
						Button(onClick = { app.machines.stop(vm.id) }) { Text("Shut down") }
						OutlinedButton(onClick = { confirmForce = true }) { Text("Force stop") }
					}
					VmState.Stopping -> OutlinedButton(onClick = { confirmForce = true }) { Text("Force stop") }
					else -> Button(onClick = { app.machines.start(vm.id) }) { Text("Start") }
				}
			}
		}
		Section(title = "Connect") {
			if (!running) Text("Start the machine to connect.", color = MaterialTheme.colorScheme.onSurfaceVariant)
			FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
				FilledTonalButton(enabled = running, onClick = { model.go(Screen.Console(vm.id)) }) { Text("Console") }
				if (vm.backend != BackendKind.TERMUX) {
					FilledTonalButton(enabled = running, onClick = { model.go(Screen.Ssh(vm.id)) }) { Text("SSH") }
					FilledTonalButton(enabled = running, onClick = { model.go(Screen.Vnc(vm.id, vm.access.vncPort)) }) { Text("VNC") }
					FilledTonalButton(enabled = running, onClick = {
						val guest = app.machines.guest(vm.id) ?: return@FilledTonalButton
						model.run("Opening RDP", { app.machines.forwarder.temporary(vm.id, vm.access.rdpPort, guest) }) { port ->
							openExternal(context, Uri.parse("rdp://full%20address=s:127.0.0.1:$port&username=s:${vm.access.user}"), "an RDP app such as aFreeRDP or Windows App", "127.0.0.1:$port", model)
						}
					}) { Text("RDP") }
					OutlinedButton(enabled = running, onClick = {
						val guest = app.machines.guest(vm.id) ?: return@OutlinedButton
						model.run("Opening SSH", { app.machines.forwarder.temporary(vm.id, vm.access.sshPort, guest) }) { port ->
							openExternal(context, Uri.parse("ssh://${vm.access.user}@127.0.0.1:$port"), "an SSH app", "ssh -p $port ${vm.access.user}@127.0.0.1", model)
						}
					}) { Text("SSH in another app") }
				}
			}
			vm.access.password?.let { password ->
				Row(verticalAlignment = Alignment.CenterVertically) {
					Text("${vm.access.user}'s password", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
					TextButton(onClick = { copy(context, password) }) { Text("Copy") }
				}
			}
		}
		Section(title = "Resources") {
			Fact("CPUs", if (vm.cpu == CpuMode.ONE) "One" else "One per core")
			Fact("Memory", "${vm.memoryMib} MiB")
			vm.boot?.let { Fact("Boots", it.plan.description.ifEmpty { "Linux" }) }
			if (vm.boot == null && vm.backend == BackendKind.AVF) Text("No kernel chosen yet, so it can't start. Edit it and pick a boot disk.", color = MaterialTheme.colorScheme.error)
		}
		Section(title = "Disks") {
			for (disk in vm.disks) {
				val usage = app.vms.diskFile(vm, disk.file).usage()
				Column {
					Text(disk.label, style = MaterialTheme.typography.bodyLarge)
					Text(
						listOfNotNull(
							when (disk.role) {
								DiskRole.ROOT -> "System"
								DiskRole.DATA -> "Data"
								DiskRole.SEED -> "cloud-init seed"
								DiskRole.CDROM -> "ISO"
							},
							if (vm.boot?.diskId == disk.id) "boots from here" else null,
							if (disk.readOnly) "read-only" else null,
							"${usage.used.gib()} used of ${usage.allocated.gib()}",
						).joinToString(" · "),
						style = MaterialTheme.typography.bodySmall,
						color = MaterialTheme.colorScheme.onSurfaceVariant,
					)
				}
			}
			TextButton(onClick = { model.go(Screen.Edit(vm.id)) }) { Text("Change disks") }
		}
		Section(title = "Network") {
			Row(verticalAlignment = Alignment.CenterVertically) {
				Column(Modifier.weight(1f)) {
					Text("Internet for the guest")
					Text(
						"AVF's own network, through the phone. Some Android builds only allow it for the Terminal app.",
						style = MaterialTheme.typography.bodySmall,
						color = MaterialTheme.colorScheme.onSurfaceVariant,
					)
				}
				Switch(
					checked = vm.network.internet,
					enabled = vm.backend == BackendKind.AVF && !running,
					onCheckedChange = { on -> app.vms.update(vm.id) { it.copy(network = it.network.copy(internet = on)) } },
				)
			}
			HorizontalDivider()
			Text("Published ports", style = MaterialTheme.typography.titleSmall)
			if (vm.network.forwards.isEmpty()) {
				Text("None. Nothing in the guest can be reached from the phone or the network until you publish a port.", style = MaterialTheme.typography.bodySmall)
			}
			for (forward in vm.network.forwards) {
				val status = forwards[forward.id]
				Row(verticalAlignment = Alignment.CenterVertically) {
					Column(Modifier.weight(1f)) {
						Text(
							"${forward.label.ifEmpty { "Port" }}: ${if (forward.exposure == Exposure.LOOPBACK) "127.0.0.1" else "all addresses"}:${forward.hostPort} → guest ${forward.guestPort}/${forward.protocol.name.lowercase()}",
							style = MaterialTheme.typography.bodyMedium,
						)
						Text(
							when {
								!forward.enabled -> "Off"
								status == null -> if (running) "Not listening" else "Listens while the machine runs"
								status.error != null -> status.error
								else -> "Listening · ${status.open} open · ${status.served} served · ${status.refused} refused"
							},
							style = MaterialTheme.typography.bodySmall,
							color = if (status?.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
						)
					}
					IconButton(onClick = { editPort = forward }) { Icon(Icons.Filled.Edit, contentDescription = "Edit port") }
				}
			}
			val temporary = forwards.values.filter { it.vmId == vm.id && it.temporary }
			for (status in temporary) {
				Text("For another app: 127.0.0.1:${status.forward.hostPort} → guest ${status.forward.guestPort}", style = MaterialTheme.typography.bodySmall)
			}
			FilledTonalButton(onClick = { editPort = PortForward(hostPort = 2222, guestPort = 22, label = "SSH") }) { Text("Publish a port") }
			if (!vm.agent && vm.backend == BackendKind.AVF) {
				Text(
					"Published ports reach the guest through Rui's guest agent, which machines made from the catalog or with first-boot setup have. This one doesn't.",
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
				)
			}
		}
		val log by app.machines.log(vm.id).collectAsState()
		if (log.isNotEmpty()) {
			Section(title = "Kernel log") {
				Text(log.takeLast(4000), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
			}
		}
		if (vm.notes.isNotBlank()) {
			Section(title = "Notes") { Text(vm.notes) }
		}
		Section(title = "Copy, move or remove") {
			Row(
					verticalAlignment = Alignment.CenterVertically,
					modifier = Modifier.toggleable(value = exportSecrets, role = Role.Checkbox, onValueChange = { exportSecrets = it }),
				) {
				Checkbox(checked = exportSecrets, onCheckedChange = null)
				Text("Include its password in exports", style = MaterialTheme.typography.bodyMedium)
			}
			FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
				FilledTonalButton(enabled = !running, onClick = { exporter.launch("${vm.name}.ruivm") }) { Text("Export") }
				FilledTonalButton(enabled = !running, onClick = {
					model.run("Copying ${vm.name}", { job -> app.operations.clone(vm, job) }) { model.replace(Screen.Detail(it.id)) }
				}) { Text("Clone") }
				OutlinedButton(enabled = !running, onClick = { confirmDelete = true }) {
					Icon(Icons.Filled.Delete, contentDescription = null)
					Text("Delete")
				}
			}
		}
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
		ConfirmDialog(
			title = "Delete ${vm.name}?",
			text = if (keepDisks) "Its settings go, and its disks move to Images." else "Its settings and disks are deleted. This can't be undone.",
			confirm = "Delete",
			onConfirm = {
				app.machines.forceStop(vm.id)
				app.vms.delete(vm.id, keepDisks, app.images)
				model.back()
			},
			onDismiss = { confirmDelete = false },
			extra = {
				Row(
						verticalAlignment = Alignment.CenterVertically,
						modifier = Modifier.toggleable(value = keepDisks, role = Role.Checkbox, onValueChange = { keepDisks = it }),
					) {
					Checkbox(checked = keepDisks, onCheckedChange = null)
					Text("Keep its disks in Images")
				}
			},
		)
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
			},
			onDelete = {
				app.vms.update(vm.id) { it.copy(network = it.network.copy(forwards = it.network.forwards.filterNot { f -> f.id == forward.id })) }
				app.machines.refreshForwards(vm.id)
				editPort = null
			},
		)
	}
}

private fun openExternal(
	context: android.content.Context,
	uri: Uri,
	what: String,
	fallback: String,
	model: AppModel,
) {
	try {
		context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
	} catch (_: ActivityNotFoundException) {
		copy(context, fallback)
		model.message.value = "No app here opens it. Install $what. Rui copied the address: $fallback"
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
						SegmentedButton(selected = protocol == option, onClick = { protocol = option }, shape = SegmentedButtonDefaults.itemShape(index, 2)) {
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
					SegmentedButton(selected = exposure == Exposure.LOOPBACK, onClick = { exposure = Exposure.LOOPBACK }, shape = SegmentedButtonDefaults.itemShape(0, 2)) {
						Text("This phone")
					}
					SegmentedButton(selected = exposure == Exposure.NETWORK, onClick = { exposure = Exposure.NETWORK }, shape = SegmentedButtonDefaults.itemShape(1, 2)) {
						Text("Network")
					}
				}
				if (exposure == Exposure.NETWORK) {
					Text("Other devices on the phone's networks can connect. Limit who with the lists below.", style = MaterialTheme.typography.bodySmall)
				}
				OutlinedTextField(
					value = allow,
					onValueChange = { allow = it },
					label = { Text("Allow only (addresses or ranges)") },
					placeholder = { Text("192.168.1.0/24") },
					isError = badAllow.isNotEmpty(),
					supportingText = { Text(if (badAllow.isNotEmpty()) "Not an address: ${badAllow.joinToString()}" else "Empty allows everyone who can reach the port") },
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
