package com.wonderhoylabs.minori.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.wonderhoylabs.minori.backend.TermuxHypervisor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Says that Android stops Termux's processes past its limit for one app, which several Termux machines or one desktop
 * go past, and opens Developer options to turn that off. Shows nothing once it's off.
 */
@Composable
fun ChildProcessNotice(what: String) {
	val context = LocalContext.current
	var limited by remember { mutableStateOf(false) }
	var checks by remember { mutableStateOf(0) }
	LifecycleResumeEffect(Unit) {
		checks++
		onPauseOrDispose {}
	}
	LaunchedEffect(checks) { limited = withContext(Dispatchers.IO) { TermuxHypervisor.childProcessLimit() } }
	if (!limited) return
	Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
		Text(
			"Android stops an app's processes once it has more than 32, and $what can go past that. Turn on Disable " +
				"child process restrictions in Developer options. If Developer options aren't there, tap Build " +
				"number seven times in About phone first.",
			style = MaterialTheme.typography.bodySmall,
			color = MaterialTheme.colorScheme.error,
		)
		FilledTonalButton(onClick = {
			try {
				context.startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
			} catch (_: ActivityNotFoundException) {
				context.startActivity(Intent(Settings.ACTION_DEVICE_INFO_SETTINGS))
			}
		}) { Text("Open Developer options") }
	}
}
