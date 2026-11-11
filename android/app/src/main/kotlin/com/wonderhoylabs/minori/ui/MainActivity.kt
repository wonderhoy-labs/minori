package com.wonderhoylabs.minori.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Storefront
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.wonderhoylabs.airi.client.Airi
import com.wonderhoylabs.minori.BuildConfig
import com.wonderhoylabs.minori.MinoriApp
import com.wonderhoylabs.minori.R
import com.wonderhoylabs.minori.backend.DemoTarget
import com.wonderhoylabs.minori.model.BackendKind
import com.wonderhoylabs.minori.model.CpuMode
import com.wonderhoylabs.minori.model.VmConfig
import com.wonderhoylabs.minori.service.AiriSetup
import com.wonderhoylabs.minori.service.Catalog
import com.wonderhoylabs.minori.service.Job
import com.wonderhoylabs.minori.setup.Privileges
import com.wonderhoylabs.minori.ui.screens.ConsoleScreen
import com.wonderhoylabs.minori.ui.screens.CreateScreen
import com.wonderhoylabs.minori.ui.screens.DetailScreen
import com.wonderhoylabs.minori.ui.screens.EditScreen
import com.wonderhoylabs.minori.ui.screens.ImagesTab
import com.wonderhoylabs.minori.ui.screens.MachinesTab
import com.wonderhoylabs.minori.ui.screens.SettingsTab
import com.wonderhoylabs.minori.ui.screens.SetupScreen
import com.wonderhoylabs.minori.ui.screens.SshScreen
import com.wonderhoylabs.minori.ui.screens.StoreItemScreen
import com.wonderhoylabs.minori.ui.screens.StoreTab
import com.wonderhoylabs.minori.ui.screens.VncScreen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class Tab(val label: String, val icon: ImageVector) {
	Machines("Machines", Icons.Rounded.Dns),
	Store("Store", Icons.Rounded.Storefront),
	Images("Images", Icons.Rounded.Album),
	Settings("Settings", Icons.Rounded.Settings),
}

/** Where the user is. The app keeps a simple back stack of these, with the tabs at the bottom of it. */
sealed interface Screen {
	data class Tabs(val tab: Tab) : Screen

	data object Setup : Screen

	data class StoreItem(val templateId: String) : Screen

	/** The settings for a new machine, from a store item, an image, or a Termux userland. */
	data class Create(val templateId: String? = null, val imageId: String? = null, val termux: Boolean = false) : Screen

	data class Detail(val vmId: String) : Screen

	data class Edit(val vmId: String) : Screen

	data class Console(val vmId: String) : Screen

	data class Ssh(val vmId: String) : Screen

	data class Vnc(val vmId: String, val guestPort: Int) : Screen
}

/** The back stack, and the one long job (copying, downloading, exporting) that may be running. */
class AppModel : ViewModel() {
	val stack = mutableStateListOf<Screen>(Screen.Tabs(Tab.Machines))
	val job = mutableStateOf<Job?>(null)
	val message = mutableStateOf<String?>(null)

	/** A machine and the token Airi handed over, until its page shows the setup dialog. */
	val airiToken = mutableStateOf<Pair<String, String>?>(null)
	private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

	val current: Screen get() = stack.last()

	fun go(screen: Screen) {
		stack.add(screen)
	}

	fun replace(screen: Screen) {
		if (stack.size > 1) stack.removeAt(stack.lastIndex)
		stack.add(screen)
	}

	/** Goes to a tab, closing whatever was open over the tabs. */
	fun tab(tab: Tab) {
		stack.clear()
		stack.add(Screen.Tabs(tab))
	}

	fun back(): Boolean {
		if (stack.size <= 1) {
			val root = stack.firstOrNull()
			if (root is Screen.Tabs && root.tab != Tab.Machines) {
				tab(Tab.Machines)
				return true
			}
			return false
		}
		stack.removeAt(stack.lastIndex)
		return true
	}

	/** Runs [work] off the main thread with a progress dialog, then [then] on the main thread with its result. */
	fun <T> run(title: String, work: (Job) -> T, then: (T) -> Unit = {}) {
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
		val app = MinoriApp.get(this)
		val privileges = Privileges.state(this)
		if (savedInstanceState == null && privileges.avfFeature && !privileges.granted && app.vms.vms.value.isEmpty()) {
			model.go(Screen.Setup)
		}
		// The screens live in the ViewModel, so an activity Android re-creates with the same intent already has them, and
		// opening Minori from Recents hands back the intent that first started it, which was already handled.
		val fromHistory = (intent?.flags ?: 0) and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0
		if (savedInstanceState == null && !fromHistory) {
			if (intent?.getBooleanExtra(Privileges.AFTER_GRANT, false) == true && model.current != Screen.Setup) {
				model.go(Screen.Setup)
			}
			handleLink(intent)
			handleAiriSetUp(intent)
			handleDevExtras(intent)
		}
		setContent {
			MinoriTheme {
				Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
					App(model)
				}
			}
		}
	}

	override fun onNewIntent(intent: Intent) {
		super.onNewIntent(intent)
		setIntent(intent)
		if (intent.getBooleanExtra(Privileges.AFTER_GRANT, false) && model.current != Screen.Setup) model.go(Screen.Setup)
		handleLink(intent)
		handleAiriSetUp(intent)
		handleDevExtras(intent)
	}

	/**
	 * Opens `minori://vm/<id or name>`, and `/console`, `/ssh` or `/vnc` after it, for other apps and for Asahi
	 * through `am start`. The machine has to be running for those three; nothing is started for the caller.
	 * `/app/<desktop file ID>` is different: it opens one Linux app through Airi, starting the machine if it has to.
	 */
	private fun handleLink(intent: Intent?) {
		val uri = intent?.data ?: return
		if (uri.scheme != "minori" || uri.host != "vm") return
		val key = uri.pathSegments.getOrNull(0) ?: return
		val app = MinoriApp.get(this)
		val vm = app.vms.get(key) ?: app.vms.vms.value.firstOrNull { it.name == key } ?: return
		model.go(Screen.Detail(vm.id))
		when (uri.pathSegments.getOrNull(1)) {
			"console" -> model.go(Screen.Console(vm.id))
			"ssh" -> model.go(Screen.Ssh(vm.id))
			"vnc" -> model.go(Screen.Vnc(vm.id, vm.access.vncPort))
			"app" -> uri.pathSegments.getOrNull(2)?.let { openLinuxApp(vm, it) }
		}
	}

	/**
	 * `minori://vm/<machine>/app/<desktop file ID>` does for a machine what Airi's `airi://app/<desktop file ID>` does
	 * for Android's terminal. Airi shows the app, so the machine needs Airi set up in it. Minori starts the machine, since
	 * it's the app on screen and so may, and hands over to Airi, which waits for its agent there through Minori.
	 */
	private fun openLinuxApp(vm: VmConfig, desktopId: String) {
		val app = MinoriApp.get(this)
		if (!AiriSetup.installed(this) || !AiriSetup.isSetUp(vm)) {
			Toast.makeText(this, getString(R.string.app_link_needs_airi, vm.name), Toast.LENGTH_LONG).show()
			return
		}
		if (!AiriSetup.knowsMachines(this)) {
			Toast.makeText(this, getString(R.string.app_link_old_airi), Toast.LENGTH_LONG).show()
			return
		}
		app.machines.start(vm.id)
		startActivity(Airi.openMinoriIntent(vm.id, desktopId))
	}

	/** Airi hands over its token to set itself up in a machine: the machine's page opens with the setup dialog. */
	private fun handleAiriSetUp(intent: Intent?) {
		if (intent?.action != ACTION_SET_UP_AIRI) return
		val app = MinoriApp.get(this)
		val vm = intent.getStringExtra(EXTRA_MACHINE)?.let(app.vms::get) ?: return
		val token = intent.getStringExtra(EXTRA_TOKEN)?.let(AiriSetup::token) ?: return
		model.tab(Tab.Machines)
		model.go(Screen.Detail(vm.id))
		model.airiToken.value = vm.id to token
	}

	/**
	 * Debug builds take extras so tests can drive real flows on the emulator, which can't run AVF:
	 * `--es dev_guest_console host:port` and `--es dev_guest_agent host:port` point the demo backend at a serial
	 * console and a minori-agent over TCP, `--es dev_open <vm id>` opens a machine's page, and
	 * `--es dev_screen setup|store|images|settings` opens a screen.
	 */
	private fun handleDevExtras(intent: Intent?) {
		if (!BuildConfig.DEBUG || intent == null) return
		val app = MinoriApp.get(this)
		val console = intent.getStringExtra("dev_guest_console")
		val agent = intent.getStringExtra("dev_guest_agent")
		if (console != null || agent != null) app.demoTarget = DemoTarget(console = console, agent = agent)
		intent.getStringExtra("dev_open")?.let { id -> if (app.vms.get(id) != null) model.go(Screen.Detail(id)) }
		// `--es dev_create <store item>`: makes a machine from the store as Get would, for tests on a locked phone,
		// with the password from `--es dev_password`. Its progress is in logcat under MinoriDev.
		intent.getStringExtra("dev_create")?.let { id ->
			val template = Catalog.template(id) ?: return@let
			val password = intent.getStringExtra("dev_password") ?: return@let
			model.run("Getting ${template.name}", { job ->
				val progress = Thread {
					while (model.job.value === job) {
						android.util.Log.i("MinoriDev", "${job.step} ${(job.fraction * 100).toInt()}%")
						Thread.sleep(5000)
					}
				}.apply { isDaemon = true }
				progress.start()
				try {
					app.operations.createFromTemplate(
						template, template.name, BackendKind.AVF, CpuMode.MATCH_HOST, template.memoryMib, template.diskGib,
						"user", password, job,
					)
				} catch (error: Throwable) {
					android.util.Log.w("MinoriDev", "failed", error)
					throw error
				}
			}) { android.util.Log.i("MinoriDev", "created ${it.id} ${it.name}") }
		}
		when (intent.getStringExtra("dev_screen")) {
			"setup" -> model.go(Screen.Setup)
			"store", "create" -> model.tab(Tab.Store)
			"images" -> model.tab(Tab.Images)
			"settings" -> model.tab(Tab.Settings)
		}
	}
}

@Composable
fun App(model: AppModel) {
	BackHandler(enabled = model.stack.size > 1 || (model.stack.first() as? Screen.Tabs)?.tab != Tab.Machines) {
		model.back()
	}
	AnimatedContent(
		targetState = model.stack.toList(),
		transitionSpec = {
			val forward = targetState.size >= initialState.size
			if (targetState.size == initialState.size && targetState.last() is Screen.Tabs) {
				fadeIn() togetherWith fadeOut()
			} else {
				(slideInHorizontally { width -> if (forward) width / 4 else -width / 4 } + fadeIn()) togetherWith
					(slideOutHorizontally { width -> if (forward) -width / 4 else width / 4 } + fadeOut())
			}
		},
		label = "screens",
	) { stack ->
		when (val screen = stack.last()) {
			is Screen.Tabs -> Tabs(model, screen.tab)
			Screen.Setup -> SetupScreen(model)
			is Screen.StoreItem -> StoreItemScreen(model, screen.templateId)
			is Screen.Create -> CreateScreen(model, screen)
			is Screen.Detail -> DetailScreen(model, screen.vmId)
			is Screen.Edit -> EditScreen(model, screen.vmId)
			is Screen.Console -> ConsoleScreen(model, screen.vmId)
			is Screen.Ssh -> SshScreen(model, screen.vmId)
			is Screen.Vnc -> VncScreen(model, screen.vmId, screen.guestPort)
		}
	}
	JobDialog(model)
	MessageDialog(model)
}

@Composable
private fun Tabs(model: AppModel, tab: Tab) {
	Scaffold(
		bottomBar = {
			NavigationBar {
				for (entry in Tab.entries) {
					NavigationBarItem(
						selected = entry == tab,
						onClick = { model.tab(entry) },
						icon = { Icon(entry.icon, contentDescription = null) },
						label = { Text(entry.label) },
					)
				}
			}
		},
		containerColor = MaterialTheme.colorScheme.background,
	) { padding ->
		Box(Modifier.fillMaxSize().padding(padding)) {
			when (tab) {
				Tab.Machines -> MachinesTab(model)
				Tab.Store -> StoreTab(model)
				Tab.Images -> ImagesTab(model)
				Tab.Settings -> SettingsTab(model)
			}
		}
	}
}

private const val ACTION_SET_UP_AIRI = "com.wonderhoylabs.minori.action.SET_UP_AIRI"
private const val EXTRA_MACHINE = "machine"
private const val EXTRA_TOKEN = "token"
