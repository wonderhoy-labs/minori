package com.wonderhoylabs.minori.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.wonderhoylabs.minori.MinoriApp
import com.wonderhoylabs.minori.model.BackendKind
import com.wonderhoylabs.minori.model.CpuMode
import com.wonderhoylabs.minori.model.DiskRole
import com.wonderhoylabs.minori.model.VmDisk
import com.wonderhoylabs.minori.model.VmState
import com.wonderhoylabs.minori.service.Operations
import com.wonderhoylabs.minori.store.usage
import com.wonderhoylabs.minori.ui.AppModel
import com.wonderhoylabs.minori.ui.Panel
import com.wonderhoylabs.minori.ui.SubScreen

/** Changes a stopped machine: name, CPUs, memory, disks, how it boots, and notes. */
@Composable
fun EditScreen(model: AppModel, vmId: String) {
	val context = LocalContext.current
	val app = MinoriApp.get(context)
	val vms by app.vms.vms.collectAsState()
	val vm = vms.firstOrNull { it.id == vmId } ?: return
	val states by app.machines.states.collectAsState()
	val stopped = (states[vm.id] ?: VmState.Stopped).let { it == VmState.Stopped || it is VmState.Failed }
	val totalMib = remember { totalMemoryMib(context) }
	// Termux machines share the phone's kernel and Termux's files, so they have no CPUs, memory or disks of their own,
	// and Minori looks after their desktop's password itself.
	val termux = vm.backend == BackendKind.TERMUX
	var name by remember(vm.id) { mutableStateOf(vm.name) }
	var cpu by remember(vm.id) { mutableStateOf(vm.cpu) }
	var memory by remember(vm.id) { mutableStateOf(vm.memoryMib.toString()) }
	var cmdline by remember(vm.id) { mutableStateOf(vm.boot?.cmdline ?: "") }
	var console by remember(vm.id) { mutableStateOf(vm.boot?.consoleDevice ?: "ttyS0") }
	var notes by remember(vm.id) { mutableStateOf(vm.notes) }
	var user by remember(vm.id) { mutableStateOf(vm.access.user) }
	var password by remember(vm.id) { mutableStateOf(vm.access.password ?: "") }
	var vncPort by remember(vm.id) { mutableStateOf(vm.access.vncPort.toString()) }
	var vncPassword by remember(vm.id) { mutableStateOf(vm.access.vncPassword ?: "") }
	var autoStart by remember(vm.id) { mutableStateOf(vm.autoStart) }
	var resizing by remember { mutableStateOf<VmDisk?>(null) }
	var addingDisk by remember { mutableStateOf(false) }
	var attaching by remember { mutableStateOf(false) }
	var leaving by remember { mutableStateOf(false) }
	val changed =
		name != vm.name || cpu != vm.cpu || memory != vm.memoryMib.toString() ||
			cmdline != (vm.boot?.cmdline ?: "") || console != (vm.boot?.consoleDevice ?: "ttyS0") || notes != vm.notes ||
			user != vm.access.user || password != (vm.access.password ?: "") ||
			vncPort != vm.access.vncPort.toString() || vncPassword != (vm.access.vncPassword ?: "") ||
			autoStart != vm.autoStart

	BackHandler(enabled = changed) { leaving = true }
	SubScreen(title = "Edit ${vm.name}", model = model, onBack = { if (changed) leaving = true else model.back() }) {
		if (!stopped) {
			Text(
				"Shut the machine down to change its resources and disks.",
				color = MaterialTheme.colorScheme.error,
			)
		}
		Panel(title = "Machine") {
			OutlinedTextField(value = name, onValueChange = {
				name = it
			}, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
			if (!termux) {
				SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
					SegmentedButton(selected = cpu == CpuMode.ONE, onClick = {
						cpu = CpuMode.ONE
					}, shape = SegmentedButtonDefaults.itemShape(0, 2), enabled = stopped) { Text("One CPU") }
					SegmentedButton(selected = cpu == CpuMode.MATCH_HOST, onClick = {
						cpu = CpuMode.MATCH_HOST
					}, shape = SegmentedButtonDefaults.itemShape(1, 2), enabled = stopped) {
						Text("One per core")
					}
				}
				OutlinedTextField(
					value = memory,
					onValueChange = { memory = it.filter(Char::isDigit) },
					label = { Text("Memory (MB)") },
					enabled = stopped,
					singleLine = true,
					keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
					isError = memoryWarning(memory.toLongOrNull() ?: 0, totalMib) != null,
					supportingText = {
						Text(
							memoryWarning(memory.toLongOrNull() ?: 0, totalMib)
								?: "This phone has ${memoryLabel(totalMib.toInt() / 1024 * 1024)}.",
						)
					},
					modifier = Modifier.fillMaxWidth(),
				)
			}
			Row(verticalAlignment = Alignment.CenterVertically) {
				Column(Modifier.weight(1f)) {
					Text("Start when the phone starts")
					Text("Only while Minori is allowed to run in the background.", style = MaterialTheme.typography.bodySmall)
				}
				Switch(checked = autoStart, onCheckedChange = { autoStart = it })
			}
		}
		if (!termux) {
			Panel(title = "Disks") {
				for (disk in vm.disks) {
					val usage = app.vms.diskFile(vm, disk.file).usage()
					Column {
						Text(
							"${disk.label} · ${formatBytes(usage.allocated)}, ${formatBytes(usage.used)} used",
							style = MaterialTheme.typography.bodyLarge,
						)
						Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
							if (disk.role != DiskRole.SEED && disk.role != DiskRole.CDROM) {
								TextButton(enabled = stopped, onClick = { resizing = disk }) { Text("Grow") }
							}
							if (vm.boot?.diskId != disk.id && disk.role != DiskRole.SEED) {
								TextButton(enabled = stopped, onClick = {
									model.run("Looking for a kernel", { app.operations.setBootDisk(vm, disk) }) {}
								}) { Text("Boot from this") }
							}
							if (vm.boot?.diskId != disk.id) {
								TextButton(enabled = stopped, onClick = {
									model.run("Removing ${disk.label}", { app.operations.removeDisk(vm, disk) }) {}
								}) { Text("Remove") }
							}
						}
					}
				}
				Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
					FilledTonalButton(enabled = stopped, onClick = { addingDisk = true }) { Text("Add an empty disk") }
					FilledTonalButton(enabled = stopped, onClick = { attaching = true }) { Text("Attach an image") }
				}
			}
		}
		if (vm.boot != null) {
			Panel(title = "Boot") {
				OutlinedTextField(value = cmdline, onValueChange = {
					cmdline = it
				}, label = { Text("Kernel command line") }, modifier = Modifier.fillMaxWidth())
				OutlinedTextField(
					value = console,
					onValueChange = { console = it.trim() },
					label = { Text("Console input device") },
					singleLine = true,
					supportingText = {
						Text("ttyS0 is AVF's serial console. The last console= on the command line gets the login prompt.")
					},
					modifier = Modifier.fillMaxWidth(),
				)
			}
		}
		Panel(title = "Logging in") {
			OutlinedTextField(value = user, onValueChange = {
				user = it
			}, label = { Text("User") }, singleLine = true, modifier = Modifier.fillMaxWidth())
			SecretField(password, { password = it }, "Password")
			if (!termux) {
				OutlinedTextField(
					value = vncPort,
					onValueChange = { vncPort = it.filter(Char::isDigit) },
					label = { Text("Guest VNC port") },
					singleLine = true,
					keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
					modifier = Modifier.fillMaxWidth(),
				)
				SecretField(vncPassword, { vncPassword = it }, "VNC password")
			}
		}
		Panel(title = "Notes") {
			OutlinedTextField(value = notes, onValueChange = { notes = it }, modifier = Modifier.fillMaxWidth(), minLines = 3)
		}
		Button(onClick = {
			app.vms.update(vm.id) {
				it.copy(
					name = name.ifBlank { it.name },
					cpu = cpu,
					memoryMib = (memory.toIntOrNull() ?: it.memoryMib).coerceAtLeast(256),
					boot = it.boot?.copy(cmdline = cmdline.trim(), consoleDevice = console.ifEmpty { "ttyS0" }),
					notes = notes,
					autoStart = autoStart,
					access =
						it.access.copy(
							user = user.ifBlank { "user" },
							password = password.ifEmpty { null },
							vncPort = vncPort.toIntOrNull() ?: it.access.vncPort,
							vncPassword = vncPassword.ifEmpty { null },
						),
				)
			}
			model.back()
		}) { Text("Save") }
	}
	resizing?.let { disk ->
		val current = app.vms.diskFile(vm, disk.file).length() / Operations.GIB
		var size by remember { mutableStateOf((current + 4).toString()) }
		AlertDialog(
			onDismissRequest = { resizing = null },
			title = { Text("Grow ${disk.label}") },
			text = {
				Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
					Text(
						"It's $current GB. Disks can only grow. Cloud images grow their filesystem on the next boot; others need growpart and resize2fs inside.",
					)
					OutlinedTextField(
						value = size,
						onValueChange = { size = it.filter(Char::isDigit) },
						label = { Text("New size (GB)") },
						keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
					)
				}
			},
			confirmButton = {
				TextButton(onClick = {
					val gib = size.toIntOrNull() ?: 0
					resizing = null
					model.run("Growing ${disk.label}", { app.operations.growDisk(vm, disk, gib) })
				}) { Text("Grow") }
			},
			dismissButton = { TextButton(onClick = { resizing = null }) { Text("Cancel") } },
		)
	}
	if (leaving) {
		AlertDialog(
			onDismissRequest = { leaving = false },
			title = { Text("Discard your changes?") },
			text = { Text("Your changes to ${vm.name} aren't saved yet.") },
			confirmButton = {
				TextButton(onClick = {
					leaving = false
					model.back()
				}) { Text("Discard") }
			},
			dismissButton = { TextButton(onClick = { leaving = false }) { Text("Keep editing") } },
		)
	}
	if (addingDisk) {
		var size by remember { mutableStateOf("8") }
		var label by remember { mutableStateOf("Data") }
		AlertDialog(
			onDismissRequest = { addingDisk = false },
			title = { Text("Add an empty disk") },
			text = {
				Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
					OutlinedTextField(value = label, onValueChange = { label = it }, label = { Text("Name") }, singleLine = true)
					OutlinedTextField(
						value = size,
						onValueChange = { size = it.filter(Char::isDigit) },
						label = { Text("Size (GB)") },
						keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
					)
					Text(
						"It appears in the guest as the next /dev/vd* disk, empty and unformatted.",
						style = MaterialTheme.typography.bodySmall,
					)
				}
			},
			confirmButton = {
				TextButton(onClick = {
					addingDisk = false
					model.run("Adding a disk", { app.operations.addBlankDisk(vm, label.ifBlank { "Data" }, size.toIntOrNull() ?: 8) })
				}) { Text("Add") }
			},
			dismissButton = { TextButton(onClick = { addingDisk = false }) { Text("Cancel") } },
		)
	}
	if (attaching) {
		val images by app.images.images.collectAsState()
		AlertDialog(
			onDismissRequest = { attaching = false },
			title = { Text("Attach an image") },
			text = {
				Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
					if (images.isEmpty()) Text("Import an image in Images first.")
					for (image in images) {
						TextButton(onClick = {
							attaching = false
							model.run("Attaching ${image.name}", { job -> app.operations.attachImage(vm, image, job) })
						}) { Text(image.name) }
					}
				}
			},
			confirmButton = { TextButton(onClick = { attaching = false }) { Text("Close") } },
		)
	}
}

/** A password field that hides what's typed until the eye is tapped. */
@Composable
private fun SecretField(value: String, onValueChange: (String) -> Unit, label: String) {
	var shown by remember { mutableStateOf(false) }
	OutlinedTextField(
		value = value,
		onValueChange = onValueChange,
		label = { Text(label) },
		singleLine = true,
		visualTransformation = if (shown) VisualTransformation.None else PasswordVisualTransformation(),
		keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
		trailingIcon = {
			IconButton(onClick = { shown = !shown }) {
				Icon(
					if (shown) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
					contentDescription = if (shown) "Hide $label" else "Show $label",
				)
			}
		},
		modifier = Modifier.fillMaxWidth(),
	)
}
