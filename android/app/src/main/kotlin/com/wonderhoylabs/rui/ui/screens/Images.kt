package com.wonderhoylabs.rui.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.wonderhoylabs.rui.RuiApp
import com.wonderhoylabs.rui.model.ImageRecord
import com.wonderhoylabs.rui.service.gib
import com.wonderhoylabs.rui.store.freeBytes
import com.wonderhoylabs.rui.ui.AppModel
import com.wonderhoylabs.rui.ui.ConfirmDialog
import com.wonderhoylabs.rui.ui.Screen
import com.wonderhoylabs.rui.ui.SubScreen

/** The image library: what the user imported or the catalog downloaded, what's in each, and what it can do. */
@Composable
fun ImagesScreen(model: AppModel) {
	val context = LocalContext.current
	val app = RuiApp.get(context)
	val images by app.images.images.collectAsState()
	var deleting by remember { mutableStateOf<ImageRecord?>(null) }
	val picker =
		rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
			if (uri != null) model.run("Importing an image", { job -> app.operations.importImage(uri, job) })
		}
	SubScreen(title = "Images", model = model) {
		Text(
			"Disk images (raw, .img, qcow2) and ISOs for 64-bit Arm. Rui checks what's inside before you use one. ${freeBytes(
				app.images.dir,
			).gib()} free.",
			style = MaterialTheme.typography.bodyMedium,
		)
		Button(onClick = { picker.launch(arrayOf("*/*")) }) { Text("Import a file") }
		for (image in images) {
			ImageCard(image, onClick = null)
			Text(
				listOfNotNull(
					image.source,
					image.sha256.takeIf {
						it.isNotEmpty()
					}?.let { "SHA-256 ${it.take(16)}…" },
				).joinToString(" · "),
				style = MaterialTheme.typography.bodySmall,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
			)
			image.report?.notes?.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
			Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
				if (image.report?.verdict == "bootable" || image.report?.verdict == "data_only") {
					TextButton(onClick = { model.go(Screen.Create(imageId = image.id)) }) { Text("Make a machine") }
				}
				TextButton(onClick = { deleting = image }) { Text("Delete") }
			}
		}
	}
	deleting?.let { image ->
		ConfirmDialog(
			title = "Delete ${image.name}?",
			text = "Machines made from it keep their own copies. This frees ${image.size.gib()}.",
			confirm = "Delete",
			onConfirm = { app.images.delete(image.id) },
			onDismiss = { deleting = null },
		)
	}
}
