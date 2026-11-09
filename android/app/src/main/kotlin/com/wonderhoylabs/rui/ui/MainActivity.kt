package com.wonderhoylabs.rui.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.wonderhoylabs.rui.BuildConfig
import com.wonderhoylabs.rui.RuiApp
import com.wonderhoylabs.rui.backend.DemoTarget
import com.wonderhoylabs.rui.service.Job
import com.wonderhoylabs.rui.setup.Privileges
import com.wonderhoylabs.rui.ui.screens.ConsoleScreen
import com.wonderhoylabs.rui.ui.screens.CreateScreen
import com.wonderhoylabs.rui.ui.screens.DetailScreen
import com.wonderhoylabs.rui.ui.screens.EditScreen
import com.wonderhoylabs.rui.ui.screens.HomeScreen
import com.wonderhoylabs.rui.ui.screens.ImagesScreen
import com.wonderhoylabs.rui.ui.screens.SettingsScreen
import com.wonderhoylabs.rui.ui.screens.SetupScreen
import com.wonderhoylabs.rui.ui.screens.SshScreen
import com.wonderhoylabs.rui.ui.screens.VncScreen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Where the user is. The app keeps a simple back stack of these. */
sealed interface Screen {
	data object Home : Screen

	data object Setup : Screen

	data object Images : Screen

	data object Settings : Screen

	data class Create(
		val imageId: String? = null,
		val templateId: String? = null,
	) : Screen

	data class Detail(
		val vmId: String,
	) : Screen

	data class Edit(
		val vmId: String,
	) : Screen

	data class Console(
		val vmId: String,
	) : Screen

	data class Ssh(
		val vmId: String,
	) : Screen

	data class Vnc(
		val vmId: String,
		val guestPort: Int,
	) : Screen
}

/** The back stack, and the one long job (copying, downloading, exporting) that may be running. */
class AppModel : ViewModel() {
	val stack = mutableStateListOf<Screen>(Screen.Home)
	val job = mutableStateOf<Job?>(null)
	val message = mutableStateOf<String?>(null)
	private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

	val current: Screen get() = stack.last()

	fun go(screen: Screen) {
		stack.add(screen)
	}

	fun replace(screen: Screen) {
		if (stack.size > 1) stack.removeAt(stack.lastIndex)
		stack.add(screen)
	}

	fun back(): Boolean {
		if (stack.size <= 1) return false
		stack.removeAt(stack.lastIndex)
		return true
	}

	/** Runs [work] off the main thread with a progress dialog, then [then] on the main thread with its result. */
	fun <T> run(
		title: String,
		work: (Job) -> T,
		then: (T) -> Unit = {},
	) {
		val running = Job(title)
		job.value = running
		scope.launch {
			val result = withContext(Dispatchers.IO) { runCatching { work(running) } }
			job.value = null
			result.onSuccess(then).onFailure { message.value = it.message ?: it.javaClass.simpleName }
		}
	}
}

class MainActivity : ComponentActivity() {
	private val model by lazy { ViewModelProvider(this)[AppModel::class.java] }

	override fun onCreate(savedInstanceState: Bundle?) {
		enableEdgeToEdge()
		super.onCreate(savedInstanceState)
		val app = RuiApp.get(this)
		val privileges = Privileges.state(this)
		if (savedInstanceState == null && privileges.avfFeature && !privileges.granted && app.vms.vms.value.isEmpty()) {
			model.go(Screen.Setup)
		}
		if (intent?.getBooleanExtra(Privileges.AFTER_GRANT, false) == true && model.current != Screen.Setup) model.go(Screen.Setup)
		handleDevExtras(intent)
		setContent {
			RuiTheme {
				Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
					App(model)
				}
			}
		}
	}

	override fun onNewIntent(intent: Intent) {
		super.onNewIntent(intent)
		if (intent.getBooleanExtra(Privileges.AFTER_GRANT, false) && model.current != Screen.Setup) model.go(Screen.Setup)
		handleDevExtras(intent)
	}

	/**
	 * Debug builds take extras so tests can drive real flows on the emulator, which can't run AVF:
	 * `--es dev_guest_console host:port` and `--es dev_guest_agent host:port` point the demo backend at a serial
	 * console and a rui-agent over TCP, `--es dev_open <vm id>` opens a machine's page, and
	 * `--es dev_screen setup|images|settings|create` opens a screen.
	 */
	private fun handleDevExtras(intent: Intent?) {
		if (!BuildConfig.DEBUG || intent == null) return
		val app = RuiApp.get(this)
		val console = intent.getStringExtra("dev_guest_console")
		val agent = intent.getStringExtra("dev_guest_agent")
		if (console != null || agent != null) app.demoTarget = DemoTarget(console = console, agent = agent)
		intent.getStringExtra("dev_open")?.let { id -> if (app.vms.get(id) != null) model.go(Screen.Detail(id)) }
		when (intent.getStringExtra("dev_screen")) {
			"setup" -> model.go(Screen.Setup)
			"images" -> model.go(Screen.Images)
			"settings" -> model.go(Screen.Settings)
			"create" -> model.go(Screen.Create())
		}
	}
}

@Composable
fun App(model: AppModel) {
	BackHandler(enabled = model.stack.size > 1) { model.back() }
	when (val screen = model.current) {
		Screen.Home -> HomeScreen(model)
		Screen.Setup -> SetupScreen(model)
		Screen.Images -> ImagesScreen(model)
		Screen.Settings -> SettingsScreen(model)
		is Screen.Create -> CreateScreen(model, screen)
		is Screen.Detail -> DetailScreen(model, screen.vmId)
		is Screen.Edit -> EditScreen(model, screen.vmId)
		is Screen.Console -> ConsoleScreen(model, screen.vmId)
		is Screen.Ssh -> SshScreen(model, screen.vmId)
		is Screen.Vnc -> VncScreen(model, screen.vmId, screen.guestPort)
	}
	JobDialog(model)
	MessageDialog(model)
}
