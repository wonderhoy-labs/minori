package com.wonderhoylabs.rui.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.wonderhoylabs.rui.RuiApp
import com.wonderhoylabs.rui.vnc.RfbClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.concurrent.thread

/** The guest's desktop over VNC, through Rui's own connection to it. Tap to click, drag to move, long-press to right-click. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VncScreen(
	model: com.wonderhoylabs.rui.ui.AppModel,
	vmId: String,
	guestPort: Int,
) {
	val app = RuiApp.get(LocalContext.current)
	val vm = app.vms.get(vmId) ?: return
	var client by remember { mutableStateOf<RfbClient?>(null) }
	var status by remember { mutableStateOf<String?>(null) }
	LaunchedEffect(vmId, guestPort) {
		val guest = app.machines.guest(vmId) ?: run {
			status = "The machine isn't running."
			return@LaunchedEffect
		}
		client =
			withContext(Dispatchers.IO) {
				runCatching {
					RfbClient(guest.connect(guestPort), vm.access.vncPassword ?: vm.access.password).also { it.handshake() }
				}.onFailure { status = "Couldn't open the desktop on guest port $guestPort: ${it.message}" }.getOrNull()
			}
		client?.let { rfb ->
			thread(name = "vnc", isDaemon = true) {
				runCatching { rfb.run() }.onFailure { status = "The desktop closed: ${it.message}" }
				client = null
			}
		}
	}
	DisposableEffect(vmId) { onDispose { client?.close() } }
	Column(modifier = Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
		val focus = remember { FocusRequester() }
		val keyboard = LocalSoftwareKeyboardController.current
		TopAppBar(
			title = { Text("${vm.name} desktop") },
			navigationIcon = { IconButton(onClick = { model.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
			actions = {
				IconButton(onClick = {
					focus.requestFocus()
					keyboard?.show()
				}) { Icon(Icons.Filled.Keyboard, contentDescription = "Keyboard") }
			},
		)
		val rfb = client
		if (rfb == null) {
			Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
				Column(horizontalAlignment = Alignment.CenterHorizontally) {
					if (status == null) CircularProgressIndicator()
					Text(status ?: "Connecting", modifier = Modifier.padding(24.dp), style = MaterialTheme.typography.bodyLarge)
				}
			}
			return@Column
		}
		val frame by rfb.frames.collectAsState()
		var view by remember { mutableStateOf(IntSize(1, 1)) }
		fun map(x: Float, y: Float): Pair<Int, Int> {
			val scale = minOf(view.width.toFloat() / rfb.width, view.height.toFloat() / rfb.height)
			val offsetX = (view.width - rfb.width * scale) / 2
			val offsetY = (view.height - rfb.height * scale) / 2
			return ((x - offsetX) / scale).toInt() to ((y - offsetY) / scale).toInt()
		}
		Box(
			modifier =
				Modifier
					.weight(1f)
					.fillMaxWidth()
					.background(Color.Black)
					.onSizeChanged { view = it }
					.pointerInput(rfb) {
						detectTapGestures(
							onTap = { offset ->
								val (x, y) = map(offset.x, offset.y)
								rfb.pointer(x, y, 1)
								rfb.pointer(x, y, 0)
							},
							onLongPress = { offset ->
								val (x, y) = map(offset.x, offset.y)
								rfb.pointer(x, y, 4)
								rfb.pointer(x, y, 0)
							},
						)
					}.pointerInput(rfb) {
						detectDragGestures(
							onDragStart = { offset ->
								val (x, y) = map(offset.x, offset.y)
								rfb.pointer(x, y, 1)
							},
							onDragEnd = {},
							onDrag = { change, _ ->
								val (x, y) = map(change.position.x, change.position.y)
								rfb.pointer(x, y, if (change.pressed) 1 else 0)
							},
						)
					},
		) {
			@Suppress("UNUSED_EXPRESSION")
			frame
			rfb.bitmap?.let { Image(it.asImageBitmap(), contentDescription = "Guest desktop", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize()) }
			var field by remember { mutableStateOf(TextFieldValue("  ", TextRange(2))) }
			BasicTextField(
				value = field,
				onValueChange = { next ->
					when {
						next.text.length > field.text.length -> rfb.type(next.text.substring(field.text.length))
						next.text.length < field.text.length -> repeat(field.text.length - next.text.length) {
							rfb.key(RfbClient.KEY_BACKSPACE, true)
							rfb.key(RfbClient.KEY_BACKSPACE, false)
						}
					}
					field = TextFieldValue("  ", TextRange(2))
				},
				modifier = Modifier.size(1.dp).focusRequester(focus),
			)
		}
		Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
			for ((label, keysym) in listOf("Esc" to RfbClient.KEY_ESCAPE, "Tab" to RfbClient.KEY_TAB, "←" to RfbClient.KEY_LEFT, "↑" to RfbClient.KEY_UP, "↓" to RfbClient.KEY_DOWN, "→" to RfbClient.KEY_RIGHT, "Enter" to RfbClient.KEY_RETURN)) {
				FilterChip(selected = false, onClick = {
					rfb.key(keysym, true)
					rfb.key(keysym, false)
				}, label = { Text(label) })
			}
		}
	}
}
