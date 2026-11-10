package com.wonderhoylabs.minori.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.DesktopWindows
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material.icons.rounded.VpnKey
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wonderhoylabs.minori.MinoriApp
import com.wonderhoylabs.minori.model.ImageRecord
import com.wonderhoylabs.minori.service.Access
import com.wonderhoylabs.minori.service.Catalog
import com.wonderhoylabs.minori.service.StoreCategory
import com.wonderhoylabs.minori.service.StoreIcon
import com.wonderhoylabs.minori.service.Template
import com.wonderhoylabs.minori.ui.AppModel
import com.wonderhoylabs.minori.ui.IconTile
import com.wonderhoylabs.minori.ui.ImageTile
import com.wonderhoylabs.minori.ui.InfoRow
import com.wonderhoylabs.minori.ui.Panel
import com.wonderhoylabs.minori.ui.Screen
import com.wonderhoylabs.minori.ui.StoreIconTile
import com.wonderhoylabs.minori.ui.SubScreen
import com.wonderhoylabs.minori.ui.tileColors

private enum class Shelf(val label: String) {
	All("All"),
	Linux("Linux"),
	Desktop("Desktops"),
	App("Apps"),
	Mine("Your images"),
}

private fun Template.matches(query: String): Boolean {
	val q = query.trim().lowercase()
	if (q.isEmpty()) return true
	return listOf(name, publisher, summary, about, category.label).any { q in it.lowercase() } ||
		features.any { q in it.lowercase() }
}

fun downloadLabel(template: Template): String {
	val mb = template.baseImage.sizeMb + template.extraDownloadMb
	return if (mb >= 1000) "%.1f GB".format(mb / 1000.0) else "$mb MB"
}

@Composable
fun StoreTab(model: AppModel) {
	val context = LocalContext.current
	val app = MinoriApp.get(context)
	val images by app.images.images.collectAsState()
	var query by rememberSaveable { mutableStateOf("") }
	var shelf by rememberSaveable { mutableStateOf(Shelf.All) }
	val importFile =
		rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
			if (uri == null) return@rememberLauncherForActivityResult
			model.run("Importing an image", { job -> app.operations.importImage(uri, job) }) {
				if (it.report?.verdict == "bootable" || it.report?.verdict == "data_only") model.go(Screen.Create(imageId = it.id))
			}
		}
	val templates =
		Catalog.templates.filter { template ->
			template.matches(query) &&
				when (shelf) {
					Shelf.All -> true
					Shelf.Linux -> template.category == StoreCategory.Linux
					Shelf.Desktop -> template.category == StoreCategory.Desktop
					Shelf.App -> template.category == StoreCategory.App
					Shelf.Mine -> false
				}
		}
	val mine =
		images.filter { image ->
			(shelf == Shelf.All || shelf == Shelf.Mine) &&
				(query.isBlank() || query.trim().lowercase() in (image.name + " " + (image.report?.os ?: "")).lowercase())
		}
	val showTermux =
		(shelf == Shelf.All || shelf == Shelf.Linux) && (query.isBlank() || "termux".contains(query.trim().lowercase()))
	LazyColumn(
		modifier = Modifier.fillMaxSize(),
		contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 24.dp),
		verticalArrangement = Arrangement.spacedBy(12.dp),
	) {
		item {
			Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
				Text("Store", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
				Text(
					"Ready-made machines from official images",
					style = MaterialTheme.typography.bodyMedium,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
				)
			}
		}
		item {
			OutlinedTextField(
				value = query,
				onValueChange = { query = it },
				placeholder = { Text("Search the store", maxLines = 1) },
				leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
				trailingIcon = {
					if (query.isNotEmpty()) {
						IconButton(onClick = {
							query = ""
						}) { Icon(Icons.Rounded.Close, contentDescription = "Clear") }
					}
				},
				singleLine = true,
				shape = CircleShape,
				colors =
					OutlinedTextFieldDefaults.colors(
						unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
						focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
						unfocusedBorderColor = MaterialTheme.colorScheme.surfaceContainerHigh,
					),
				modifier = Modifier.fillMaxWidth(),
			)
		}
		item {
			Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
				for (entry in Shelf.entries) {
					FilterChip(selected = shelf == entry, onClick = {
						shelf = entry
					}, label = { Text(entry.label) }, shape = CircleShape)
				}
			}
		}
		if (shelf == Shelf.All && query.isBlank()) {
			item { Featured(Catalog.templates.first()) { model.go(Screen.StoreItem(it.id)) } }
		}
		items(templates.drop(if (shelf == Shelf.All && query.isBlank()) 1 else 0), key = { it.id }) { template ->
			StoreRow(template) { model.go(Screen.StoreItem(template.id)) }
		}
		if (showTermux) {
			item {
				ListCard(
					tile = { StoreIconTile(StoreIcon.Termux) },
					title = "Linux in Termux",
					subtitle = "Termux · proot",
					text = "Debian, Ubuntu and more in Termux, on any phone. It shares the phone's kernel; it isn't a VM.",
					button = "Set up",
					onClick = { model.go(Screen.Create(termux = true)) },
				)
			}
		}
		if (shelf == Shelf.All || shelf == Shelf.Mine) {
			item {
				Text(
					"Your images",
					style = MaterialTheme.typography.titleMedium,
					fontWeight = FontWeight.SemiBold,
					modifier = Modifier.padding(top = 12.dp),
				)
			}
			items(mine, key = { it.id }) { image -> ImageRow(image) { model.go(Screen.Create(imageId = image.id)) } }
			item {
				ListCard(
					tile = { IconTile(Icons.Rounded.Add, tileColors(0)) },
					title = "Bring your own",
					subtitle = "Disk image or ISO",
					text = "A raw disk, .img, qcow2 or ISO for 64-bit Arm. Minori checks what's inside first.",
					button = "Import",
					onClick = { importFile.launch(arrayOf("*/*")) },
				)
			}
		}
		if (templates.isEmpty() && mine.isEmpty() && !showTermux) {
			item {
				Text(
					"Nothing matches \"$query\".",
					style = MaterialTheme.typography.bodyLarge,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
					modifier = Modifier.padding(vertical = 24.dp),
				)
			}
		}
	}
}

@Composable
private fun Featured(template: Template, onOpen: (Template) -> Unit) {
	val scheme = MaterialTheme.colorScheme
	Card(
		onClick = { onOpen(template) },
		shape = RoundedCornerShape(28.dp),
		colors = CardDefaults.cardColors(containerColor = scheme.primaryContainer),
		modifier = Modifier.fillMaxWidth(),
	) {
		Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
			Text(
				"FEATURED",
				style = MaterialTheme.typography.labelMedium,
				color = scheme.onPrimaryContainer,
				fontWeight = FontWeight.Bold,
			)
			Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
				StoreIconTile(template.icon, size = 64.dp)
				Column(Modifier.weight(1f)) {
					Text(
						template.name,
						style = MaterialTheme.typography.headlineSmall,
						fontWeight = FontWeight.SemiBold,
						color = scheme.onPrimaryContainer,
					)
					Text(template.publisher, style = MaterialTheme.typography.bodyMedium, color = scheme.onPrimaryContainer)
				}
			}
			Text(template.summary, style = MaterialTheme.typography.bodyLarge, color = scheme.onPrimaryContainer)
			Button(onClick = { onOpen(template) }) { Text("Get") }
		}
	}
}

@Composable
private fun StoreRow(template: Template, onOpen: () -> Unit) {
	ListCard(
		tile = { StoreIconTile(template.icon) },
		title = template.name,
		subtitle = "${template.category.label.removeSuffix("s")} · ${downloadLabel(template)}",
		text = template.summary,
		button = "Get",
		onClick = onOpen,
	)
}

@Composable
private fun ImageRow(image: ImageRecord, onUse: () -> Unit) {
	val report = image.report
	val usable = report?.verdict == "bootable" || report?.verdict == "data_only"
	ListCard(
		tile = { ImageTile(report?.format == "iso") },
		title = report?.os ?: image.name,
		subtitle = listOfNotNull(
			report?.format?.uppercase(),
			report?.virtualSize?.let {
				formatBytes(it)
			},
		).joinToString(" · "),
		text = verdictText(image),
		button = if (usable) "Use" else null,
		onClick = onUse,
	)
}

/** One row of the store: an icon, a name, a line about it, and a button. */
@Composable
fun ListCard(
	tile: @Composable () -> Unit,
	title: String,
	subtitle: String,
	text: String,
	button: String?,
	onClick: () -> Unit,
) {
	Card(
		onClick = onClick,
		enabled = button != null,
		shape = RoundedCornerShape(24.dp),
		colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
		modifier = Modifier.fillMaxWidth(),
	) {
		Row(
			Modifier.padding(16.dp),
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.spacedBy(14.dp),
		) {
			tile()
			Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
				Text(
					title,
					style = MaterialTheme.typography.titleMedium,
					fontWeight = FontWeight.SemiBold,
					maxLines = 1,
					overflow = TextOverflow.Ellipsis,
				)
				Text(
					subtitle,
					style = MaterialTheme.typography.labelMedium,
					color = MaterialTheme.colorScheme.primary,
					maxLines = 1,
				)
				Text(
					text,
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
					maxLines = 2,
					overflow = TextOverflow.Ellipsis,
				)
			}
			if (button != null) FilledTonalButton(onClick = onClick) { Text(button) }
		}
	}
}

fun formatBytes(bytes: Long): String = when {
	bytes >= 1L shl 30 -> "%.1f GB".format(bytes / (1L shl 30).toDouble())
	bytes >= 1L shl 20 -> "${bytes shr 20} MB"
	else -> "${bytes shr 10} KB"
}

fun verdictText(image: ImageRecord): String {
	val report = image.report ?: return "Not looked at yet"
	return when (report.verdict) {
		"bootable" -> "Ready to boot: ${report.boot?.description ?: "Linux"}"

		"wrong_arch" -> "Made for x86-64 PCs. A phone needs a 64-bit Arm (aarch64) image."

		"windows" -> "Windows, which can't run here."

		else ->
			if (report.format == "iso") {
				"No installer Minori knows on this ISO. It can be attached to a machine as a disc."
			} else {
				"No operating system found. It can be attached to a machine as a data disk."
			}
	}
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun StoreItemScreen(model: AppModel, templateId: String) {
	val template = Catalog.template(templateId) ?: return
	val scheme = MaterialTheme.colorScheme
	SubScreen(title = "", model = model) {
		Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
			StoreIconTile(template.icon, size = 96.dp)
			Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
				Text(template.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
				Text(template.publisher, style = MaterialTheme.typography.bodyMedium, color = scheme.primary)
				Text(
					"${template.category.label.removeSuffix("s")} · ${downloadLabel(template)}",
					style = MaterialTheme.typography.labelMedium,
					color = scheme.onSurfaceVariant,
				)
			}
		}
		Button(onClick = {
			model.go(Screen.Create(templateId = template.id))
		}, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
			Text("Get", modifier = Modifier.padding(vertical = 4.dp))
		}
		Text(template.summary, style = MaterialTheme.typography.titleMedium)
		Text(template.about, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
		Panel(title = "What you get") {
			for (feature in template.features) {
				Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
					Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = scheme.primary, modifier = Modifier.size(20.dp))
					Text(feature, style = MaterialTheme.typography.bodyLarge)
				}
			}
		}
		Panel(title = "Ways in") {
			FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
				for (access in template.access) {
					AssistChip(
						onClick = {},
						label = { Text(access.label) },
						leadingIcon = {
							Icon(
								when (access) {
									Access.Console -> Icons.Rounded.Terminal
									Access.Ssh -> Icons.Rounded.VpnKey
									Access.Vnc, Access.Rdp -> Icons.Rounded.DesktopWindows
								},
								contentDescription = null,
								modifier = Modifier.size(18.dp),
							)
						},
					)
				}
			}
		}
		Panel(title = "Needs") {
			InfoRow(Icons.Rounded.Memory, "Memory", "${memoryLabel(template.memoryMib)} suggested")
			InfoRow(Icons.Rounded.Storage, "Storage", "${template.diskGib} GB disk, which only takes what it uses")
			InfoRow(Icons.Rounded.CloudDownload, "Download", downloadLabel(template))
			InfoRow(Icons.Rounded.Schedule, "First boot", template.firstBoot.replaceFirstChar { it.uppercase() })
		}
		Panel(title = "Source") {
			InfoRow(
				Icons.Rounded.Verified,
				"${template.baseImage.project}'s official image",
				"From ${java.net.URI(template.baseImage.url).host}, checked against " +
					"${template.baseImage.project}'s ${template.baseImage.algorithm} sums",
			)
		}
	}
}
