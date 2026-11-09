package com.wonderhoylabs.rui.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.wonderhoylabs.rui.RuiApp
import com.wonderhoylabs.rui.terminal.Ssh
import com.wonderhoylabs.rui.terminal.TerminalEnded
import com.wonderhoylabs.rui.terminal.TerminalMeasure
import com.wonderhoylabs.rui.terminal.TerminalSession
import com.wonderhoylabs.rui.terminal.TerminalView
import com.wonderhoylabs.rui.ui.AppModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File

/** A full-screen terminal with a top bar, used by the console and SSH. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TerminalFrame(
	title: String,
	model: AppModel,
	session: TerminalSession?,
	status: String?,
	onMeasured: () -> Unit = {},
) {
	@Suppress("DEPRECATION")
	val clipboard = LocalClipboardManager.current
	Column(modifier = Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
		TopAppBar(
			title = { Text(title) },
			navigationIcon = { IconButton(onClick = { model.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
			actions = {
				if (session != null) {
					IconButton(onClick = { clipboard.getText()?.text?.let { session.paste(it) } }) {
						Icon(Icons.Filled.ContentPaste, contentDescription = "Paste")
					}
				}
			},
		)
		when {
			session != null -> {
				val ended by session.endReason.collectAsState()
				ended?.let { TerminalEnded(it) { model.back() } }
				TerminalView(session, modifier = Modifier.weight(1f).fillMaxWidth())
			}
			else ->
				Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
					TerminalMeasure(modifier = Modifier.fillMaxSize(), onMeasured = onMeasured)
					Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
						if (status == null) CircularProgressIndicator()
						Text(status ?: "Connecting", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(24.dp))
					}
				}
		}
	}
}

/** The VM's serial console, as Android's Terminal app shows it, but kept open while the VM runs. */
@Composable
fun ConsoleScreen(
	model: AppModel,
	vmId: String,
) {
	val app = RuiApp.get(LocalContext.current)
	val vm = app.vms.get(vmId)
	val session = app.machines.console(vmId)
	TerminalFrame(
		title = "${vm?.name ?: "VM"} console",
		model = model,
		session = session,
		status = if (session == null) "The machine isn't running." else null,
	)
}

/** SSH into the guest through Rui's own connection to it, so no port has to be published. */
@Composable
fun SshScreen(
	model: AppModel,
	vmId: String,
) {
	val app = RuiApp.get(LocalContext.current)
	val vm = app.vms.get(vmId) ?: return
	var session by remember { mutableStateOf<TerminalSession?>(null) }
	var status by remember { mutableStateOf<String?>(null) }
	var hostKey by remember { mutableStateOf<Pair<String, CompletableDeferred<Boolean>>?>(null) }
	var measured by remember { mutableStateOf(false) }
	LaunchedEffect(vmId, measured) {
		if (!measured) return@LaunchedEffect
		val guest = app.machines.guest(vmId)
		if (guest == null) {
			status = "The machine isn't running."
			return@LaunchedEffect
		}
		val dir = app.vms.dir(vmId)
		val key = File(dir, "ssh/id_rsa").takeIf { it.exists() }?.readText()
		session =
			withContext(Dispatchers.IO) {
				runCatching {
					Ssh.shell(
						title = vm.name,
						hostAlias = "vm-$vmId.rui",
						user = vm.access.user,
						password = vm.access.password,
						privateKey = key,
						knownHosts = File(dir, "ssh/known_hosts"),
						open = { guest.connect(vm.access.sshPort) },
						confirmHostKey = { message ->
							val answer = CompletableDeferred<Boolean>()
							hostKey = message to answer
							runBlocking { answer.await() }.also { hostKey = null }
						},
					)
				}.onFailure { status = "Couldn't log in over SSH: ${it.message}" }.getOrNull()
			}
	}
	DisposableEffect(vmId) { onDispose { session?.close() } }
	TerminalFrame(title = "${vm.name} over SSH", model = model, session = session, status = status, onMeasured = { measured = true })
	hostKey?.let { (message, answer) ->
		AlertDialog(
			onDismissRequest = { answer.complete(false) },
			title = { Text("Trust this guest?") },
			text = { Text(message) },
			confirmButton = { TextButton(onClick = { answer.complete(true) }) { Text("Trust and connect") } },
			dismissButton = { TextButton(onClick = { answer.complete(false) }) { Text("Cancel") } },
		)
	}
}
