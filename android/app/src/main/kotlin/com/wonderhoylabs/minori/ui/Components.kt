package com.wonderhoylabs.minori.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.wonderhoylabs.minori.R
import com.wonderhoylabs.minori.backend.TermuxHypervisor
import com.wonderhoylabs.minori.model.BackendKind
import com.wonderhoylabs.minori.model.VmConfig
import com.wonderhoylabs.minori.model.VmState
import com.wonderhoylabs.minori.service.Catalog
import com.wonderhoylabs.minori.service.StoreIcon
import kotlinx.coroutines.delay

/** The colours of one icon tile, always from the theme's container roles. */
data class TileColors(val container: Color, val content: Color)

@Composable
fun tileColors(index: Int): TileColors {
	val scheme = MaterialTheme.colorScheme
	return when (index % 3) {
		0 -> TileColors(scheme.primaryContainer, scheme.onPrimaryContainer)
		1 -> TileColors(scheme.tertiaryContainer, scheme.onTertiaryContainer)
		else -> TileColors(scheme.secondaryContainer, scheme.onSecondaryContainer)
	}
}

/** A Material Symbol on a rounded, coloured square: how machines and store items are told apart at a glance. */
@Composable
fun IconTile(icon: ImageVector, colors: TileColors, modifier: Modifier = Modifier, size: Dp = 52.dp) {
	Box(
		modifier = modifier.size(size).clip(RoundedCornerShape(size * 0.3f)).background(colors.container),
		contentAlignment = Alignment.Center,
	) {
		Icon(icon, contentDescription = null, tint = colors.content, modifier = Modifier.size(size * 0.5f))
	}
}

/** The project's own logo for a store item, or null where Minori has none and shows a symbol instead. */
private fun logo(icon: StoreIcon): Int? = when (icon) {
	StoreIcon.Debian -> R.drawable.logo_debian
	StoreIcon.Ubuntu -> R.drawable.logo_ubuntu
	StoreIcon.Fedora -> R.drawable.logo_fedora
	StoreIcon.Kde -> R.drawable.logo_kde
	StoreIcon.Xfce -> R.drawable.logo_xfce
	StoreIcon.Gnome -> R.drawable.logo_gnome
	StoreIcon.Chromium -> R.drawable.logo_chromium
	StoreIcon.Firefox -> R.drawable.logo_firefox
	StoreIcon.LibreOffice -> R.drawable.logo_libreoffice
	StoreIcon.Gimp -> R.drawable.logo_gimp
	StoreIcon.Inkscape -> R.drawable.logo_inkscape
	StoreIcon.Krita -> R.drawable.logo_krita
	StoreIcon.Thunderbird -> R.drawable.logo_thunderbird
	StoreIcon.Vlc -> R.drawable.logo_vlc
	StoreIcon.Blender -> R.drawable.logo_blender
	StoreIcon.Termux, StoreIcon.Linux -> null
}

/**
 * A store item's or machine's icon: the project's real logo on a white tile, as app stores show icons. Termux shows
 * the installed Termux app's own icon. Anything else gets a terminal symbol.
 */
@Composable
fun StoreIconTile(icon: StoreIcon, modifier: Modifier = Modifier, size: Dp = 52.dp) {
	val shape = RoundedCornerShape(size * 0.3f)
	val logo = logo(icon)
	if (logo != null) {
		Box(modifier = modifier.size(size).clip(shape).background(Color.White), contentAlignment = Alignment.Center) {
			Image(painterResource(logo), contentDescription = null, modifier = Modifier.size(size * 0.68f))
		}
		return
	}
	val context = LocalContext.current
	val appIcon =
		remember(icon) {
			if (icon != StoreIcon.Termux) {
				null
			} else {
				runCatching {
					context.packageManager.getApplicationIcon(TermuxHypervisor.TERMUX).toBitmap(192, 192).asImageBitmap()
				}.getOrNull()
			}
		}
	if (appIcon != null) {
		Image(appIcon, contentDescription = null, modifier = modifier.size(size).clip(shape))
	} else {
		IconTile(Icons.Rounded.Terminal, tileColors(2), modifier, size)
	}
}

/** The icon a machine shows: its template's, or one guessed from its OS. */
fun machineIcon(vm: VmConfig): StoreIcon {
	Catalog.template(vm.template)?.let { return it.icon }
	val os = (vm.os ?: vm.distro ?: "").lowercase()
	return when {
		vm.backend == BackendKind.TERMUX -> StoreIcon.Termux
		"ubuntu" in os -> StoreIcon.Ubuntu
		"fedora" in os -> StoreIcon.Fedora
		"debian" in os -> StoreIcon.Debian
		else -> StoreIcon.Linux
	}
}

@Composable
fun MachineTile(vm: VmConfig, size: Dp = 52.dp) {
	StoreIconTile(machineIcon(vm), size = size)
}

@Composable
fun ImageTile(iso: Boolean) {
	IconTile(if (iso) Icons.Rounded.Album else Icons.Rounded.Storage, tileColors(2), size = 48.dp)
}

/** A machine's state as a small pill, with a dot that breathes while it runs. */
@Composable
fun StatusPill(state: VmState, modifier: Modifier = Modifier) {
	val scheme = MaterialTheme.colorScheme
	val (label, dot) =
		when (state) {
			VmState.Running -> "Running" to scheme.tertiary
			VmState.Starting -> "Starting" to scheme.primary
			VmState.Stopping -> "Stopping" to scheme.primary
			VmState.Stopped -> "Stopped" to scheme.outline
			is VmState.Failed -> "Needs attention" to scheme.error
		}
	val color by animateColorAsState(dot, label = "status")
	val pulse = rememberInfiniteTransition(label = "pulse")
	val alpha by pulse.animateFloat(
		initialValue = 1f,
		targetValue = if (state == VmState.Running || state == VmState.Starting) 0.3f else 1f,
		animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
		label = "alpha",
	)
	Row(
		modifier =
			modifier
				.clip(CircleShape)
				.background(scheme.surfaceContainerHighest)
				.padding(horizontal = 10.dp, vertical = 4.dp),
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.spacedBy(6.dp),
	) {
		Box(Modifier.size(8.dp).alpha(alpha).clip(CircleShape).background(color))
		Text(label, style = MaterialTheme.typography.labelMedium, color = scheme.onSurface)
	}
}

/** A big, friendly button for one way into a machine: console, SSH, desktop. */
@Composable
fun RowScope.ActionTile(icon: ImageVector, label: String, enabled: Boolean, onClick: () -> Unit) {
	val scheme = MaterialTheme.colorScheme
	Column(
		modifier =
			Modifier
				.weight(1f)
				.clip(RoundedCornerShape(20.dp))
				.background(if (enabled) scheme.secondaryContainer else scheme.surfaceContainerHigh)
				.clickable(enabled = enabled, onClick = onClick)
				.padding(vertical = 14.dp)
				.alpha(if (enabled) 1f else 0.45f),
		horizontalAlignment = Alignment.CenterHorizontally,
		verticalArrangement = Arrangement.spacedBy(6.dp),
	) {
		Icon(icon, contentDescription = null, tint = scheme.onSecondaryContainer)
		Text(label, style = MaterialTheme.typography.labelLarge, color = scheme.onSecondaryContainer, maxLines = 1)
	}
}

/** A label, a value, and an icon in front, for facts on a card. */
@Composable
fun InfoRow(icon: ImageVector, label: String, value: String, trailing: @Composable (() -> Unit)? = null) {
	Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
		Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
		Column(Modifier.weight(1f)) {
			Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
			Text(value, style = MaterialTheme.typography.bodyLarge)
		}
		trailing?.invoke()
	}
}

/** A rounded card with an optional heading, for one group of things on a page. */
@Composable
fun Panel(
	title: String? = null,
	modifier: Modifier = Modifier,
	action: @Composable (() -> Unit)? = null,
	content: @Composable ColumnScope.() -> Unit,
) {
	Card(
		modifier = modifier.fillMaxWidth().animateContentSize(),
		shape = RoundedCornerShape(24.dp),
		colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
	) {
		Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
			if (title != null) {
				Row(verticalAlignment = Alignment.CenterVertically) {
					Text(
						title,
						style = MaterialTheme.typography.titleMedium,
						fontWeight = FontWeight.SemiBold,
						modifier = Modifier.weight(1f),
					)
					action?.invoke()
				}
			}
			content()
		}
	}
}

/** What a list shows when it's empty: a big icon, a sentence, and what to do next. */
@Composable
fun EmptyState(icon: ImageVector, title: String, text: String, action: @Composable (() -> Unit)? = null) {
	Column(
		modifier = Modifier.fillMaxWidth().padding(vertical = 40.dp, horizontal = 24.dp),
		horizontalAlignment = Alignment.CenterHorizontally,
		verticalArrangement = Arrangement.spacedBy(12.dp),
	) {
		IconTile(icon, tileColors(0), size = 88.dp)
		Spacer(Modifier.height(4.dp))
		Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
		Text(
			text,
			style = MaterialTheme.typography.bodyMedium,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
			textAlign = TextAlign.Center,
		)
		action?.invoke()
	}
}

/** A page below a tab: a top bar with a back arrow, and scrolling content with the usual gutters. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubScreen(
	title: String,
	model: AppModel,
	actions: @Composable RowScope.() -> Unit = {},
	scroll: Boolean = true,
	content: @Composable ColumnScope.() -> Unit,
) {
	Column(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
		TopAppBar(
			title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
			navigationIcon = {
				IconButton(onClick = { model.back() }) {
					Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
				}
			},
			actions = actions,
			colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
		)
		val modifier = if (scroll) Modifier.verticalScroll(rememberScrollState()) else Modifier
		Column(
			modifier =
				Modifier
					.weight(1f)
					.fillMaxWidth()
					.then(modifier)
					.padding(horizontal = 20.dp)
					.padding(bottom = 24.dp)
					.widthIn(max = 840.dp),
			verticalArrangement = Arrangement.spacedBy(16.dp),
			content = content,
		)
	}
}

/** A command or secret in monospace with a Copy button. */
@Composable
fun CommandBox(command: String) {
	val context = LocalContext.current
	Surface(color = MaterialTheme.colorScheme.surfaceContainerHighest, shape = RoundedCornerShape(14.dp)) {
		Row(modifier = Modifier.fillMaxWidth().padding(start = 14.dp), verticalAlignment = Alignment.CenterVertically) {
			SelectionContainer(modifier = Modifier.weight(1f).padding(vertical = 12.dp)) {
				Text(command, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
			}
			IconButton(onClick = { copy(context, command) }) { Icon(Icons.Rounded.ContentCopy, contentDescription = "Copy") }
		}
	}
}

fun copy(context: Context, text: String) {
	context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Minori", text))
}

fun backendLabel(kind: BackendKind): String = when (kind) {
	BackendKind.AVF -> "Virtual machine"
	BackendKind.DEMO -> "Demo shell"
	BackendKind.TERMUX -> "Termux"
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
			Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
				Text(step.ifEmpty { "Working" })
				if (job.total > 0) {
					LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
					Text("${(fraction * 100).toInt()}%", style = MaterialTheme.typography.labelMedium)
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

/** A label on the left and its value on the right, for plain facts. */
@Composable
fun Fact(label: String, value: String) {
	Row(verticalAlignment = Alignment.CenterVertically) {
		Text(
			label,
			style = MaterialTheme.typography.bodyMedium,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
			modifier = Modifier.weight(1f),
		)
		Text(
			value,
			style = MaterialTheme.typography.bodyMedium,
			fontWeight = FontWeight.Medium,
			textAlign = TextAlign.End,
			modifier = Modifier.weight(1f),
		)
	}
}
