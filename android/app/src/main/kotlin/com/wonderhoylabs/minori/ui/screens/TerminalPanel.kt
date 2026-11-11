package com.wonderhoylabs.minori.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.wonderhoylabs.minori.MinoriApp
import com.wonderhoylabs.minori.net.NetworkState
import com.wonderhoylabs.minori.net.TerminalAgent
import com.wonderhoylabs.minori.net.TerminalLink
import com.wonderhoylabs.minori.ui.AppModel
import com.wonderhoylabs.minori.ui.CommandBox
import com.wonderhoylabs.minori.ui.Panel
import com.wonderhoylabs.minori.ui.copy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private const val CHECK_EVERY_MS = 3000L

/**
 * Lets Minori carry the traffic of Android's own Linux terminal, which otherwise never goes through a VPN app. See
 * [TerminalLink].
 */
@Composable
fun TerminalPanel(model: AppModel) {
	val context = LocalContext.current
	val link = MinoriApp.get(context).terminal
	var resumed by remember { mutableIntStateOf(0) }
	LifecycleResumeEffect(Unit) {
		resumed++
		onPauseOrDispose {}
	}
	val hasTerminal = remember(resumed) { link.terminalIntent() != null }
	var agent by remember { mutableStateOf<TerminalAgent?>(null) }
	LaunchedEffect(resumed) {
		while (true) {
			agent = withContext(Dispatchers.IO) { link.check() }
			delay(CHECK_EVERY_MS)
		}
	}
	val network by link.network.collectAsState()
	val state = network?.state?.collectAsState()?.value
	var command by remember { mutableStateOf<String?>(null) }

	Panel {
		Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
			Icon(Icons.Rounded.Terminal, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
			Column(Modifier.weight(1f)) {
				Text("Android's terminal", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
				Text(
					when {
						!hasTerminal -> "This phone doesn't have Android's Linux terminal. It's in Developer options."

						network == null && agent == TerminalAgent.Ready ->
							"Set up. Turn this on to send the terminal's traffic through Minori, and so through any VPN."

						network == null ->
							"Its Linux uses the phone's network directly, never a VPN app like Saki, Akiyama or Shira. " +
								"Set up Minori's agent in it to send its traffic through Minori instead."

						state == NetworkState.Connected -> "Its traffic goes through Minori, and any VPN the phone uses."

						agent == TerminalAgent.WrongToken -> "Its agent came from another install of Minori. Set it up again."

						else -> "Waiting for its Linux to start, with Minori's agent set up and port ${TerminalLink.PORT} allowed…"
					},
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
				)
			}
			Switch(
				checked = network != null,
				enabled = hasTerminal,
				onCheckedChange = { on -> link.setEnabled(on) },
			)
		}
		if (hasTerminal) {
			Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
				val setUp = agent == TerminalAgent.Ready
				val prepare = {
					model.run("Preparing Minori's agent", { link.prepareInstall() }) { command = it }
				}
				if (setUp) {
					TextButton(onClick = prepare) { Text("Set up again") }
				} else {
					FilledTonalButton(onClick = prepare) { Text("Set up") }
				}
			}
		}
	}

	command?.let { text ->
		AlertDialog(
			onDismissRequest = { command = null },
			title = { Text("Set up the terminal") },
			text = {
				Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
					Text(
						"1. Run this in Android's terminal. It installs Minori's agent in its Linux.",
						style = MaterialTheme.typography.bodyMedium,
					)
					CommandBox(text.trim())
					Text(
						"2. If Android asks about port ${TerminalLink.PORT}, tap Allow always. Then come back and turn " +
							"this on.",
						style = MaterialTheme.typography.bodyMedium,
					)
				}
			},
			confirmButton = {
				TextButton(onClick = {
					copy(context, text)
					command = null
					link.terminalIntent()?.let(context::startActivity)
				}) {
					Icon(Icons.Rounded.Terminal, contentDescription = null, modifier = Modifier.size(18.dp))
					Text("  Copy and open the terminal")
				}
			},
			dismissButton = { TextButton(onClick = { command = null }) { Text("Close") } },
		)
	}
}
