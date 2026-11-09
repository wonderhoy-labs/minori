package com.wonderhoylabs.rui.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wonderhoylabs.rui.model.BackendKind
import com.wonderhoylabs.rui.model.VmState
import kotlinx.coroutines.delay

/** A screen below the main one: a top bar with a back arrow, and scrolling content with the usual gutters. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubScreen(
	title: String,
	model: AppModel,
	actions: @Composable () -> Unit = {},
	scroll: Boolean = true,
	content: @Composable ColumnScope.() -> Unit,
) {
	Column(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
		TopAppBar(
			title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
			navigationIcon = {
				IconButton(onClick = { model.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
			},
			actions = { actions() },
			colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
		)
		val modifier = if (scroll) Modifier.verticalScroll(rememberScrollState()) else Modifier
		Column(
			modifier =
				Modifier
					.weight(1f)
					.fillMaxWidth()
					.then(modifier)
					.padding(horizontal = 24.dp, vertical = 8.dp)
					.widthIn(max = 840.dp),
			verticalArrangement = Arrangement.spacedBy(16.dp),
			content = content,
		)
	}
}

/** A titled card for one group of settings or facts. */
@Composable
fun Section(
	title: String,
	modifier: Modifier = Modifier,
	highlight: Boolean = false,
	content: @Composable ColumnScope.() -> Unit,
) {
	Card(
		modifier = modifier.fillMaxWidth(),
		colors =
			if (highlight) {
				CardDefaults.cardColors(
					containerColor = MaterialTheme.colorScheme.primaryContainer,
					contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
				)
			} else {
				CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
			},
	) {
		Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
			Text(title, style = MaterialTheme.typography.titleMedium)
			content()
		}
	}
}

@Composable
fun Fact(label: String, value: String) {
	Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
		Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
		Spacer(Modifier.size(12.dp))
		Text(value, style = MaterialTheme.typography.bodyMedium)
	}
}

/** A command in monospace with a Copy button, for the adb setup steps. */
@Composable
fun CommandBox(command: String) {
	val context = LocalContext.current
	Surface(color = MaterialTheme.colorScheme.surfaceContainerHighest, shape = RoundedCornerShape(12.dp)) {
		Row(modifier = Modifier.fillMaxWidth().padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
			SelectionContainer(modifier = Modifier.weight(1f).padding(vertical = 10.dp)) {
				Text(command, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
			}
			IconButton(onClick = { copy(context, command) }) { Icon(Icons.Filled.ContentCopy, contentDescription = "Copy") }
		}
	}
}

fun copy(context: Context, text: String) {
	context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Rui", text))
}

fun stateLabel(state: VmState): String = when (state) {
	VmState.Stopped -> "Stopped"
	VmState.Starting -> "Starting"
	VmState.Running -> "Running"
	VmState.Stopping -> "Stopping"
	is VmState.Failed -> "Error"
}

fun backendLabel(kind: BackendKind): String = when (kind) {
	BackendKind.AVF -> "AVF VM"
	BackendKind.DEMO -> "Demo shell"
	BackendKind.TERMUX -> "Termux"
}

@Composable
fun StateChip(state: VmState) {
	val colours =
		when (state) {
			VmState.Running ->
				AssistChipDefaults.assistChipColors(
					containerColor = MaterialTheme.colorScheme.tertiaryContainer,
					labelColor = MaterialTheme.colorScheme.onTertiaryContainer,
				)

			is VmState.Failed ->
				AssistChipDefaults.assistChipColors(
					containerColor = MaterialTheme.colorScheme.errorContainer,
					labelColor = MaterialTheme.colorScheme.onErrorContainer,
				)

			else -> AssistChipDefaults.assistChipColors(containerColor = Color.Transparent)
		}
	AssistChip(onClick = {}, label = { Text(stateLabel(state)) }, colors = colours)
}

@Composable
fun JobDialog(model: AppModel) {
	val job = model.job.value ?: return
	var step by remember { mutableStateOf(job.step) }
	var fraction by remember { mutableFloatStateOf(job.fraction) }
	LaunchedEffect(job) {
		while (true) {
			step = job.step
			fraction = job.fraction
			delay(200)
		}
	}
	AlertDialog(
		onDismissRequest = {},
		title = { Text(job.title) },
		text = {
			Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
				Text(step.ifEmpty { "Working" })
				if (job.total >
					0
				) {
					LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
				} else {
					LinearProgressIndicator(Modifier.fillMaxWidth())
				}
			}
		},
		confirmButton = { TextButton(onClick = { job.cancelled = true }) { Text("Cancel") } },
	)
}

@Composable
fun MessageDialog(model: AppModel) {
	val message = model.message.value ?: return
	AlertDialog(
		onDismissRequest = { model.message.value = null },
		text = { Text(message) },
		confirmButton = { TextButton(onClick = { model.message.value = null }) { Text("OK") } },
	)
}

@Composable
fun ConfirmDialog(
	title: String,
	text: String,
	confirm: String,
	onConfirm: () -> Unit,
	onDismiss: () -> Unit,
	extra: @Composable (() -> Unit)? = null,
) {
	AlertDialog(
		onDismissRequest = onDismiss,
		title = { Text(title) },
		text = {
			Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
				Text(text)
				extra?.invoke()
			}
		},
		confirmButton = {
			TextButton(onClick = {
				onConfirm()
				onDismiss()
			}) { Text(confirm) }
		},
		dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
	)
}

val ScreenPadding = PaddingValues(horizontal = 24.dp, vertical = 16.dp)
