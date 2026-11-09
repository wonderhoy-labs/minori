package com.wonderhoylabs.rui.ui.screens

import android.app.ActivityManager
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.wonderhoylabs.rui.BuildConfig
import com.wonderhoylabs.rui.RuiApp
import com.wonderhoylabs.rui.backend.Availability
import com.wonderhoylabs.rui.backend.TermuxHypervisor
import com.wonderhoylabs.rui.model.AccessSettings
import com.wonderhoylabs.rui.model.BackendKind
import com.wonderhoylabs.rui.model.CpuMode
import com.wonderhoylabs.rui.model.ImageRecord
import com.wonderhoylabs.rui.model.VmConfig
import com.wonderhoylabs.rui.service.Catalog
import com.wonderhoylabs.rui.service.FirstBoot
import com.wonderhoylabs.rui.service.Operations
import com.wonderhoylabs.rui.service.Template
import com.wonderhoylabs.rui.service.gib
import com.wonderhoylabs.rui.ui.AppModel
import com.wonderhoylabs.rui.ui.CommandBox
import com.wonderhoylabs.rui.ui.Screen
import com.wonderhoylabs.rui.ui.Section
import com.wonderhoylabs.rui.ui.SubScreen

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

/** What the machine is made from. */
private sealed interface Source {
	data class FromTemplate(
		val template: Template,
	) : Source

	data class FromImage(
		val image: ImageRecord,
	) : Source

	data object Termux : Source
}

@Composable
fun CreateScreen(
	model: AppModel,
	screen: Screen.Create,
) {
	val context = LocalContext.current
	val app = RuiApp.get(context)
	val images by app.images.images.collectAsState()
	var source by remember {
		mutableStateOf<Source?>(
			screen.imageId?.let { id -> app.images.get(id)?.let { Source.FromImage(it) } }
				?: screen.templateId?.let { id -> Catalog.templates.firstOrNull { it.id == id }?.let { Source.FromTemplate(it) } },
		)
	}
	val importFile =
		rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
			if (uri == null) return@rememberLauncherForActivityResult
			model.run("Importing an image", { job -> app.operations.importImage(uri, job) }) { source = Source.FromImage(it) }
		}

	val chosen = source
	if (chosen == null) {
		SubScreen(title = "New machine", model = model) {
			Text("Catalog", style = MaterialTheme.typography.titleMedium)
			for (template in Catalog.templates) {
				TemplateCard(template) { source = Source.FromTemplate(template) }
			}
			Text("Your images", style = MaterialTheme.typography.titleMedium)
			if (images.isEmpty()) {
				Text("Nothing imported yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
			}
			for (image in images) {
				ImageCard(image, onClick = { source = Source.FromImage(image) })
			}
			FilledTonalButton(onClick = { importFile.launch(arrayOf("*/*")) }) { Text("Import a disk image or ISO") }
			Text("Without a hypervisor", style = MaterialTheme.typography.titleMedium)
			Card(
				modifier = Modifier.fillMaxWidth().clickable { source = Source.Termux },
				colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
			) {
				Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
					Text("Termux userland", style = MaterialTheme.typography.titleMedium)
					Text(
						"Debian, Ubuntu, Arch, Alpine or Fedora in Termux with proot. Works on any phone, but it isn't a VM: it shares the phone's kernel.",
						style = MaterialTheme.typography.bodyMedium,
					)
				}
			}
		}
		return
	}
	SettingsForm(model, chosen, onBack = { source = null })
}

@Composable
private fun TemplateCard(
	template: Template,
	onClick: () -> Unit,
) {
	val available = template.unavailable == null
	Card(
		modifier = Modifier.fillMaxWidth().then(if (available) Modifier.clickable(onClick = onClick) else Modifier),
		colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
	) {
		Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
			Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
				Text(template.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
				AssistChip(onClick = {}, label = { Text(template.kind.label) })
			}
			Text(template.description, style = MaterialTheme.typography.bodyMedium)
			template.unavailable?.let { Text("Not available: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
			template.caveat?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
		}
	}
}

@Composable
fun ImageCard(
	image: ImageRecord,
	onClick: (() -> Unit)?,
) {
	val report = image.report
	Card(
		modifier = Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
		colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
	) {
		Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
			Text(image.name, style = MaterialTheme.typography.titleMedium)
			Text(
				listOfNotNull(
					report?.format?.uppercase(),
					report?.os,
					report?.arch?.let { if (it == "aarch64") "64-bit Arm" else if (it == "x86_64") "x86-64" else "Unknown CPU" },
					"${image.size.gib()} file",
					report?.virtualSize?.let { "${it.gib()} disk" },
				).joinToString(" · "),
				style = MaterialTheme.typography.bodySmall,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
			)
			Text(verdictText(image), style = MaterialTheme.typography.bodyMedium)
		}
	}
}

fun verdictText(image: ImageRecord): String {
	val report = image.report ?: return "Not inspected"
	return when (report.verdict) {
		"bootable" -> "Bootable: ${report.boot?.description ?: "Linux"}"
		"wrong_arch" -> "Won't run here: it's for x86-64 PCs, and a phone is 64-bit Arm. Look for an ARM64 or aarch64 image."
		"windows" -> "Windows, which Rui can't boot."
		else ->
			if (report.format == "iso") {
				"Rui found no installer kernel it knows on this ISO. It can still be attached to a VM as a read-only disk."
			} else {
				"No kernel found, so it can't boot by itself. It can be attached to a VM as a data disk."
			}
	}
}

@Composable
private fun SettingsForm(
	model: AppModel,
	source: Source,
	onBack: () -> Unit,
) {
	val context = LocalContext.current
	val app = RuiApp.get(context)
	val template = (source as? Source.FromTemplate)?.template
	val image = (source as? Source.FromImage)?.image
	val totalMib = remember { totalMemoryMib(context) }
	var name by rememberSaveable { mutableStateOf(template?.name ?: image?.report?.os?.substringBefore(" GNU")?.trim() ?: "Linux") }
	var cpu by rememberSaveable { mutableStateOf(if (template?.cpusAll == false) CpuMode.ONE else CpuMode.MATCH_HOST) }
	var memory by rememberSaveable { mutableStateOf((template?.memoryMib ?: defaultMemoryMib(context)).toString()) }
	val minimumDisk = ((image?.report?.virtualSize ?: 0L) / Operations.GIB + 1).toInt()
	var disk by rememberSaveable { mutableStateOf(maxOf(template?.diskGib ?: 8, minimumDisk).toString()) }
	val defaultBackend =
		when {
			source == Source.Termux -> BackendKind.TERMUX
			app.machines.availability(BackendKind.AVF) == Availability.Ready || !BuildConfig.DEBUG -> BackendKind.AVF
			else -> BackendKind.DEMO
		}
	var backend by rememberSaveable { mutableStateOf(defaultBackend) }
	var user by rememberSaveable { mutableStateOf("user") }
	var password by rememberSaveable { mutableStateOf(Operations.generatePassword()) }
	var distro by rememberSaveable { mutableStateOf(TermuxHypervisor.DISTROS.first().id) }
	val cloudImage = template != null || (image?.report?.format != "iso" && image?.report?.bootable == true)
	var firstBoot by rememberSaveable { mutableStateOf(cloudImage) }

	SubScreen(title = template?.let { "New ${it.name} machine" } ?: if (source == Source.Termux) "New Termux userland" else "New machine", model = model) {
		image?.let { ImageCard(it, onClick = null) }
		template?.let { Text(it.description) }
		Section(title = "Machine") {
			OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
			if (source != Source.Termux) {
				Text("Runs on", style = MaterialTheme.typography.labelLarge)
				val backends = listOfNotNull(BackendKind.AVF, BackendKind.DEMO.takeIf { BuildConfig.DEBUG })
				SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
					backends.forEachIndexed { index, kind ->
						SegmentedButton(
							selected = backend == kind,
							onClick = { backend = kind },
							shape = SegmentedButtonDefaults.itemShape(index, backends.size),
						) { Text(if (kind == BackendKind.AVF) "AVF VM" else "Demo shell") }
					}
				}
				if (backend == BackendKind.DEMO) {
					Text(
						"The demo shell is a shell on this phone, not a VM. The disk is still made, so you can switch to AVF on a phone that has it.",
						style = MaterialTheme.typography.bodySmall,
						color = MaterialTheme.colorScheme.onSurfaceVariant,
					)
				}
				Text("Virtual CPUs", style = MaterialTheme.typography.labelLarge)
				SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
					SegmentedButton(selected = cpu == CpuMode.ONE, onClick = { cpu = CpuMode.ONE }, shape = SegmentedButtonDefaults.itemShape(0, 2)) {
						Text("One")
					}
					SegmentedButton(
						selected = cpu == CpuMode.MATCH_HOST,
						onClick = { cpu = CpuMode.MATCH_HOST },
						shape = SegmentedButtonDefaults.itemShape(1, 2),
					) { Text("One per core (${Runtime.getRuntime().availableProcessors()})") }
				}
				Text(
					"AVF offers either one virtual CPU or one per core of the phone, nothing in between.",
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
				)
				OutlinedTextField(
					value = memory,
					onValueChange = { memory = it.filter(Char::isDigit) },
					label = { Text("Memory (MiB)") },
					singleLine = true,
					keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
					modifier = Modifier.fillMaxWidth(),
					supportingText = { memoryWarning(memory.toLongOrNull() ?: 0, totalMib)?.let { Text(it) } ?: Text("This phone has ${totalMib / 1024} GiB.") },
					isError = memoryWarning(memory.toLongOrNull() ?: 0, totalMib) != null,
				)
				OutlinedTextField(
					value = disk,
					onValueChange = { disk = it.filter(Char::isDigit) },
					label = { Text("System disk (GiB)") },
					singleLine = true,
					keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
					modifier = Modifier.fillMaxWidth(),
					supportingText = { Text("It only takes the space the guest writes. At least $minimumDisk GiB for this image.") },
				)
			}
		}
		if (source == Source.Termux) {
			Section(title = "Distribution") {
				for (option in TermuxHypervisor.DISTROS) {
					Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { distro = option.id }) {
						androidx.compose.material3.RadioButton(selected = distro == option.id, onClick = { distro = option.id })
						Text(option.name)
					}
				}
				Text("Termux has to allow Rui to run commands. In Termux, run:", style = MaterialTheme.typography.bodySmall)
				CommandBox("mkdir -p ~/.termux && echo 'allow-external-apps = true' >> ~/.termux/termux.properties")
			}
		}
		if (cloudImage || source == Source.Termux) {
			Section(title = "First boot") {
				if (cloudImage) {
					Row(verticalAlignment = Alignment.CenterVertically) {
						Checkbox(checked = firstBoot, onCheckedChange = { firstBoot = it })
						Text("Set up a user, SSH and Rui's guest agent with cloud-init")
					}
				}
				if (firstBoot || source == Source.Termux) {
					if (source != Source.Termux) {
						OutlinedTextField(value = user, onValueChange = { user = it.lowercase().filter { c -> c.isLetterOrDigit() || c == '-' || c == '_' } }, label = { Text("User") }, singleLine = true, modifier = Modifier.fillMaxWidth())
					}
					Text(if (source == Source.Termux) "root's password" else "Password", style = MaterialTheme.typography.labelLarge)
					CommandBox(password)
					Text(
						"Write it down or copy it now. Rui also keeps it on this phone so the Connect buttons can log in, and makes an SSH key for this machine.",
						style = MaterialTheme.typography.bodySmall,
						color = MaterialTheme.colorScheme.onSurfaceVariant,
					)
				}
			}
		}
		Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
			FilledTonalButton(onClick = onBack) { Text("Back") }
			Button(onClick = {
				val memoryMib = (memory.toIntOrNull() ?: 2048).coerceAtLeast(256)
				val diskGib = (disk.toIntOrNull() ?: 8).coerceAtLeast(minimumDisk)
				val boot =
					if (firstBoot && cloudImage) {
						FirstBoot(
							hostname = name.lowercase().replace(Regex("[^a-z0-9-]"), "-").trim('-').ifEmpty { "rui" },
							user = user.ifEmpty { "user" },
							password = password,
							packages = template?.packages ?: listOf("openssh-server"),
							extraRun =
								template?.run.orEmpty().map {
									it.replace("{user}", user).replace("{vncpassword}", password.take(8))
								},
						)
					} else {
						null
					}
				when (source) {
					Source.Termux ->
						model.run("Making $name", { _ ->
							val vm = VmConfig(name = name, backend = BackendKind.TERMUX, distro = distro, access = AccessSettings(user = "root", password = password))
							app.vms.save(vm)
							vm
						}) { model.replace(Screen.Detail(it.id)) }
					is Source.FromImage ->
						model.run("Making $name", { job ->
							app.operations.createFromImage(name, source.image, backend, cpu, memoryMib, diskGib, boot, job)
						}) { model.replace(Screen.Detail(it.id)) }
					is Source.FromTemplate -> {
						val base = source.template.baseImage ?: return@Button
						model.run("Making $name", { job ->
							val fetched = Catalog.fetch(base, app.images, job)
							val vm = app.operations.createFromImage(name, fetched, backend, cpu, memoryMib, diskGib, boot, job, template = source.template.id)
							source.template.vncPort?.let { port ->
								app.vms.update(vm.id) { it.copy(access = it.access.copy(vncPort = port, vncPassword = password.take(8))) }
							} ?: vm
						}) { model.replace(Screen.Detail(it.id)) }
					}
				}
			}) { Text("Create") }
		}
	}
}

fun memoryWarning(
	mib: Long,
	totalMib: Long,
): String? =
	when {
		mib < 256 -> "Linux needs at least 256 MiB."
		mib > totalMib * 3 / 4 -> "That's more than this phone can spare. Android will stop apps, or the VM, to find it."
		mib > totalMib / 2 -> "That's over half of this phone's memory. Other apps may be closed while it runs."
		else -> null
	}
