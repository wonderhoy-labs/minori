package com.wonderhoylabs.minori.ui.screens

import android.app.ActivityManager
import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.wonderhoylabs.minori.BuildConfig
import com.wonderhoylabs.minori.MinoriApp
import com.wonderhoylabs.minori.backend.Availability
import com.wonderhoylabs.minori.backend.TermuxHypervisor
import com.wonderhoylabs.minori.model.AccessSettings
import com.wonderhoylabs.minori.model.BackendKind
import com.wonderhoylabs.minori.model.CpuMode
import com.wonderhoylabs.minori.model.ImageRecord
import com.wonderhoylabs.minori.model.VmConfig
import com.wonderhoylabs.minori.service.Catalog
import com.wonderhoylabs.minori.service.FirstBoot
import com.wonderhoylabs.minori.service.Operations
import com.wonderhoylabs.minori.service.Template
import com.wonderhoylabs.minori.ui.AppModel
import com.wonderhoylabs.minori.ui.CommandBox
import com.wonderhoylabs.minori.ui.IconTile
import com.wonderhoylabs.minori.ui.ImageTile
import com.wonderhoylabs.minori.ui.Panel
import com.wonderhoylabs.minori.ui.Screen
import com.wonderhoylabs.minori.ui.SubScreen
import com.wonderhoylabs.minori.ui.storeIcon
import com.wonderhoylabs.minori.ui.storeTile
import com.wonderhoylabs.minori.ui.tileColors

fun totalMemoryMib(context: Context): Long {
	val info = ActivityManager.MemoryInfo()
	context.getSystemService(ActivityManager::class.java).getMemoryInfo(info)
	return info.totalMem / (1024 * 1024)
}

/** A conservative default: 2 GiB on phones with 8 GiB or more, a quarter of memory on smaller ones. */
fun defaultMemoryMib(context: Context): Int {
	val total = totalMemoryMib(context)
	return if (total >= 8 * 1024) 2048 else ((total / 4) / 256 * 256).coerceAtLeast(512).toInt()
}

fun memoryWarning(mib: Long, totalMib: Long): String? = when {
	mib < 256 -> "Linux needs at least 256 MB."
	mib > totalMib * 3 / 4 -> "More than this phone can spare. Android will close apps, or the machine, to find it."
	mib > totalMib / 2 -> "Over half of this phone's memory. Other apps may close while it runs."
	else -> null
}

private fun hostname(name: String) = name.lowercase().replace(Regex("[^a-z0-9-]"), "-").trim('-').ifEmpty { "minori" }

@Composable
fun CreateScreen(model: AppModel, screen: Screen.Create) {
	val context = LocalContext.current
	val app = MinoriApp.get(context)
	val template = Catalog.template(screen.templateId)
	val image = screen.imageId?.let { app.images.get(it) }
	val termux = screen.termux
	if (template == null && image == null && !termux) return
	val totalMib = remember { totalMemoryMib(context) }
	val cores = Runtime.getRuntime().availableProcessors()
	var name by rememberSaveable {
		mutableStateOf(
			template?.name ?: image?.report?.os?.substringBefore(" GNU")?.trim() ?: if (termux) "Termux Linux" else "Linux",
		)
	}
	var cpu by rememberSaveable { mutableStateOf(CpuMode.MATCH_HOST) }
	val maxMemory = (totalMib * 3 / 4 / 256 * 256).toInt().coerceAtLeast(1024)
	var memory by rememberSaveable {
		mutableFloatStateOf((template?.memoryMib ?: defaultMemoryMib(context)).coerceAtMost(maxMemory).toFloat())
	}
	val minimumDisk = ((image?.report?.virtualSize ?: 0L) / Operations.GIB + 1).toInt().coerceAtLeast(2)
	var disk by rememberSaveable { mutableFloatStateOf(maxOf(template?.diskGib ?: 8, minimumDisk).toFloat()) }
	val defaultBackend =
		when {
			termux -> BackendKind.TERMUX
			app.machines.availability(BackendKind.AVF) == Availability.Ready || !BuildConfig.DEBUG -> BackendKind.AVF
			else -> BackendKind.DEMO
		}
	var backend by rememberSaveable { mutableStateOf(defaultBackend) }
	var user by rememberSaveable { mutableStateOf("user") }
	val password by rememberSaveable { mutableStateOf(Operations.generatePassword()) }
	var distro by rememberSaveable { mutableStateOf(TermuxHypervisor.DISTROS.first().id) }
	// Store items always set up the guest; an imported cloud image may or may not want cloud-init.
	val cloudImage = template != null || (image?.report?.format != "iso" && image?.report?.bootable == true)
	var firstBoot by rememberSaveable { mutableStateOf(cloudImage) }
	val warning = memoryWarning(memory.toLong(), totalMib)

	SubScreen(title = "New machine", model = model) {
		SourceHeader(template, image, termux)
		Panel(title = "Name") {
			OutlinedTextField(
				value = name,
				onValueChange = { name = it },
				singleLine = true,
				shape = RoundedCornerShape(14.dp),
				modifier = Modifier.fillMaxWidth(),
			)
		}
		if (!termux) {
			Panel(title = "Size") {
				SliderRow(
					label = "Memory",
					value = memoryLabel(memory.toInt()),
					note = warning ?: "This phone has ${memoryLabel(totalMib.toInt() / 1024 * 1024)}.",
					warn = warning != null,
				) {
					Slider(
						value = memory,
						onValueChange = { memory = (it / 256).toInt() * 256f },
						valueRange = 512f..maxMemory.toFloat(),
					)
				}
				SliderRow(
					label = "Disk",
					value = "${disk.toInt()} GB",
					note = "It only takes the space the guest writes.",
					warn = false,
				) {
					Slider(
						value = disk,
						onValueChange = { disk = it.toInt().toFloat() },
						valueRange =
							minimumDisk.toFloat()..maxOf(128, minimumDisk + 8).toFloat(),
					)
				}
				Text("Processors", style = MaterialTheme.typography.labelLarge)
				SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
					SegmentedButton(
						selected = cpu == CpuMode.MATCH_HOST,
						onClick = { cpu = CpuMode.MATCH_HOST },
						shape = SegmentedButtonDefaults.itemShape(0, 2),
					) { Text("All $cores") }
					SegmentedButton(
						selected = cpu == CpuMode.ONE,
						onClick = { cpu = CpuMode.ONE },
						shape = SegmentedButtonDefaults.itemShape(1, 2),
					) { Text("One") }
				}
			}
			if (BuildConfig.DEBUG) {
				Panel(title = "Runs on") {
					val backends = listOf(BackendKind.AVF, BackendKind.DEMO)
					SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
						backends.forEachIndexed { index, kind ->
							SegmentedButton(
								selected = backend == kind,
								onClick = { backend = kind },
								shape = SegmentedButtonDefaults.itemShape(index, backends.size),
							) { Text(if (kind == BackendKind.AVF) "Virtual machine" else "Demo shell") }
						}
					}
					Text(
						"Debug builds only. The demo shell is a shell on this phone that stands in for a VM on the " +
							"emulator.",
						style = MaterialTheme.typography.bodySmall,
						color = MaterialTheme.colorScheme.onSurfaceVariant,
					)
				}
			}
		} else {
			Panel(title = "Distribution") {
				for (option in TermuxHypervisor.DISTROS) {
					Row(
						verticalAlignment = Alignment.CenterVertically,
						modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { distro = option.id },
					) {
						RadioButton(selected = distro == option.id, onClick = { distro = option.id })
						Text(option.name, style = MaterialTheme.typography.bodyLarge)
					}
				}
			}
		}
		Panel(title = "Login") {
			if (image != null && cloudImage) {
				Row(verticalAlignment = Alignment.CenterVertically) {
					Column(Modifier.weight(1f)) {
						Text("Set it up on first boot", style = MaterialTheme.typography.bodyLarge)
						Text(
							"A user, SSH, internet and Minori's guest agent, with cloud-init",
							style = MaterialTheme.typography.bodySmall,
							color = MaterialTheme.colorScheme.onSurfaceVariant,
						)
					}
					Switch(checked = firstBoot, onCheckedChange = { firstBoot = it })
				}
			}
			if (firstBoot || termux) {
				if (!termux) {
					OutlinedTextField(
						value = user,
						onValueChange = { user = it.lowercase().filter { c -> c.isLetterOrDigit() || c == '-' || c == '_' } },
						label = { Text("User") },
						singleLine = true,
						shape = RoundedCornerShape(14.dp),
						keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
						modifier = Modifier.fillMaxWidth(),
					)
				}
				Text(if (termux) "root's password" else "Password", style = MaterialTheme.typography.labelLarge)
				CommandBox(password)
				Text(
					"Minori keeps it on this phone and logs in for you; copy it if you'll connect from somewhere else.",
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
				)
			} else {
				Text(
					"The image's own users and passwords apply.",
					style = MaterialTheme.typography.bodyMedium,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
				)
			}
		}
		if (termux) {
			Panel(title = "Before you start") {
				Text("Termux has to let Minori run commands. In Termux, run:", style = MaterialTheme.typography.bodyMedium)
				CommandBox("mkdir -p ~/.termux && echo 'allow-external-apps = true' >> ~/.termux/termux.properties")
			}
		}
		Button(
			onClick = {
				val memoryMib = memory.toInt().coerceAtLeast(256)
				val diskGib = disk.toInt().coerceAtLeast(minimumDisk)
				val boot =
					if (firstBoot && cloudImage) {
						FirstBoot(
							hostname = hostname(name),
							user = user.ifEmpty { "user" },
							password = password,
							packages = template?.packages.orEmpty(),
							extraRun =
								template?.run.orEmpty().map {
									it.replace("{user}", user.ifEmpty { "user" }).replace("{vncpassword}", password.take(8))
								},
						)
					} else {
						null
					}
				when {
					termux ->
						model.run("Making $name", { _ ->
							VmConfig(
								name = name,
								backend = BackendKind.TERMUX,
								distro = distro,
								access = AccessSettings(user = "root", password = password),
							).also { app.vms.save(it) }
						}) { model.replace(Screen.Detail(it.id)) }

					image != null ->
						model.run("Making $name", { job ->
							app.operations.createFromImage(name, image, backend, cpu, memoryMib, diskGib, boot, job)
						}) { model.replace(Screen.Detail(it.id)) }

					template != null ->
						model.run("Getting $name", { job ->
							val fetched = Catalog.fetch(template.baseImage, app.images, job)
							val vm =
								app.operations.createFromImage(
									name, fetched, backend, cpu, memoryMib, diskGib, boot, job, template = template.id,
								)
							template.vncPort?.let { port ->
								val vnc = password.take(8)
								app.vms.update(vm.id) { it.copy(access = it.access.copy(vncPort = port, vncPassword = vnc)) }
							} ?: vm
						}) { model.replace(Screen.Detail(it.id)) }
				}
			},
			enabled = name.isNotBlank(),
			modifier = Modifier.fillMaxWidth(),
		) { Text(if (template != null) "Download and create" else "Create", modifier = Modifier.padding(vertical = 6.dp)) }
	}
}

@Composable
private fun SourceHeader(template: Template?, image: ImageRecord?, termux: Boolean) {
	Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
		when {
			template != null -> IconTile(storeIcon(template.icon), tileColors(storeTile(template.icon)), size = 64.dp)
			image != null -> ImageTile(image.report?.format == "iso")
			else -> IconTile(Icons.Rounded.Terminal, tileColors(2), size = 64.dp)
		}
		Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
			Text(
				template?.name ?: image?.report?.os ?: image?.name ?: "Linux in Termux",
				style = MaterialTheme.typography.titleLarge,
				fontWeight = FontWeight.SemiBold,
			)
			Text(
				when {
					template != null -> "${downloadLabel(template)} to download · first boot ${template.firstBoot}"
					image != null -> verdictText(image)
					termux -> "A proot userland in Termux. It shares the phone's kernel."
					else -> ""
				},
				style = MaterialTheme.typography.bodyMedium,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
			)
		}
	}
}

@Composable
private fun SliderRow(label: String, value: String, note: String, warn: Boolean, slider: @Composable () -> Unit) {
	Column {
		Row(verticalAlignment = Alignment.CenterVertically) {
			Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
			Text(
				value,
				style = MaterialTheme.typography.titleMedium,
				color = MaterialTheme.colorScheme.primary,
				fontWeight = FontWeight.SemiBold,
			)
		}
		slider()
		Text(
			note,
			style = MaterialTheme.typography.bodySmall,
			color = if (warn) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
		)
	}
}
