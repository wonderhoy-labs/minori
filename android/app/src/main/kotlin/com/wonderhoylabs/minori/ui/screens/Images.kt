package com.wonderhoylabs.minori.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.FileOpen
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wonderhoylabs.minori.MinoriApp
import com.wonderhoylabs.minori.model.ImageRecord
import com.wonderhoylabs.minori.service.gib
import com.wonderhoylabs.minori.store.freeBytes
import com.wonderhoylabs.minori.store.usage
import com.wonderhoylabs.minori.ui.AppModel
import com.wonderhoylabs.minori.ui.ConfirmDialog
import com.wonderhoylabs.minori.ui.EmptyState
import com.wonderhoylabs.minori.ui.ImageTile
import com.wonderhoylabs.minori.ui.Screen

/** The image library: what the user imported or the store downloaded, what's in each, and what it can do. */
@Composable
fun ImagesTab(model: AppModel) {
	val context = LocalContext.current
	val app = MinoriApp.get(context)
	val images by app.images.images.collectAsState()
	var deleting by remember { mutableStateOf<ImageRecord?>(null) }
	val picker =
		rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
			if (uri != null) model.run("Importing an image", { job -> app.operations.importImage(uri, job) })
		}
	Box(Modifier.fillMaxSize()) {
		LazyColumn(
			modifier = Modifier.fillMaxSize(),
			contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 104.dp),
			verticalArrangement = Arrangement.spacedBy(12.dp),
		) {
			item {
				Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
					Text("Images", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
					Text(
						"${images.size} ${if (images.size == 1) "image" else "images"} · ${freeBytes(app.images.dir).gib()} free",
						style = MaterialTheme.typography.bodyMedium,
						color = MaterialTheme.colorScheme.onSurfaceVariant,
					)
				}
			}
			if (images.isEmpty()) {
				item {
					EmptyState(
						Icons.Rounded.Album,
						"No images yet",
						"Disk images and ISOs you import, and what the store downloads, are kept here so new machines start faster.",
					) {
						FilledTonalButton(onClick = { picker.launch(arrayOf("*/*")) }) { Text("Import a file") }
					}
				}
			}
			items(images, key = { it.id }) { image ->
				ImageCard(
					image,
					onUse = { model.go(Screen.Create(imageId = image.id)) },
					onDelete = { deleting = image },
				)
			}
		}
		if (images.isNotEmpty()) {
			ExtendedFloatingActionButton(
				onClick = { picker.launch(arrayOf("*/*")) },
				icon = { Icon(Icons.Rounded.FileOpen, contentDescription = null) },
				text = { Text("Import") },
				modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp),
			)
		}
	}
	deleting?.let { image ->
		ConfirmDialog(
			title = "Delete ${image.report?.os ?: image.name}?",
			text = "Machines made from it keep their own copies. This frees ${app.images.file(image).usage().used.gib()}.",
			confirm = "Delete",
			onConfirm = { app.images.delete(image.id) },
			onDismiss = { deleting = null },
		)
	}
}

@Composable
private fun ImageCard(image: ImageRecord, onUse: () -> Unit, onDelete: () -> Unit) {
	val report = image.report
	val usable = report?.verdict == "bootable" || report?.verdict == "data_only"
	var menu by remember { mutableStateOf(false) }
	Card(
		shape = RoundedCornerShape(24.dp),
		colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
		modifier = Modifier.fillMaxWidth(),
	) {
		Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
			Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
				ImageTile(report?.format == "iso")
				Column(Modifier.weight(1f)) {
					Text(
						report?.os ?: image.name,
						style = MaterialTheme.typography.titleMedium,
						fontWeight = FontWeight.SemiBold,
						maxLines = 1,
						overflow = TextOverflow.Ellipsis,
					)
					Text(
						listOfNotNull(
							report?.format?.uppercase(),
							report?.arch?.let {
								if (it == "aarch64") {
									"64-bit Arm"
								} else if (it == "x86_64") {
									"x86-64"
								} else {
									null
								}
							},
							formatBytes(image.size),
						).joinToString(" · "),
						style = MaterialTheme.typography.labelMedium,
						color = MaterialTheme.colorScheme.primary,
					)
				}
				Box {
					IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, contentDescription = "More") }
					DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
						DropdownMenuItem(text = { Text("Delete") }, onClick = {
							menu = false
							onDelete()
						})
					}
				}
			}
			Text(verdictText(image), style = MaterialTheme.typography.bodyMedium)
			image.source.takeIf { it.isNotEmpty() }?.let {
				Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
			}
			report?.notes?.forEach {
				Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
			}
			if (usable) {
				FilledTonalButton(onClick = onUse) {
					Text("Make a machine")
				}
			}
		}
	}
}
