package com.wonderhoylabs.minori.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import com.wonderhoylabs.minori.MinoriApp
import com.wonderhoylabs.minori.R
import com.wonderhoylabs.minori.ui.MainActivity

/**
 * Keeps Minori's process alive while machines run, and while a page over adb is using the helper. An AVF VM belongs to
 * the process that started it, so without a foreground service Android could stop Minori, and every VM with it, as
 * soon as the app left the screen; and Android freezes a background app, which would stop the helper answering.
 */
class MinoriService : Service() {
	private val handler = Handler(Looper.getMainLooper())

	// While only a page keeps Minori running, look again now and then, and stop once it's gone.
	private val recheck = Runnable { update(this) }

	override fun onBind(intent: Intent?): IBinder? = null

	override fun onDestroy() {
		handler.removeCallbacks(recheck)
		super.onDestroy()
	}

	override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
		val machines = MinoriApp.get(this).machines
		if (intent?.action == ACTION_STOP_ALL) {
			machines.stopAll()
		}
		// A page woke Minori over adb: `am start-foreground-service -a …WAKE -n …/.service.MinoriService`.
		if (intent?.action == ACTION_WAKE) MinoriApp.get(this).helper.touch()
		val count = machines.runningCount()
		val helper = MinoriApp.get(this).helper.inUse()
		handler.removeCallbacks(recheck)
		if (count == 0 && !helper) {
			stopForeground(STOP_FOREGROUND_REMOVE)
			stopSelf()
			return START_NOT_STICKY
		}
		if (count == 0) handler.postDelayed(recheck, RECHECK_MS)
		val notification = notification(count)
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
			startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
		} else {
			startForeground(NOTIFICATION_ID, notification)
		}
		return START_NOT_STICKY
	}

	private fun notification(count: Int): Notification {
		val manager = getSystemService(NotificationManager::class.java)
		manager.createNotificationChannel(
			NotificationChannel(CHANNEL, getString(R.string.notification_channel), NotificationManager.IMPORTANCE_LOW),
		)
		val open =
			PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
		val stop =
			PendingIntent.getService(
				this,
				1,
				Intent(this, MinoriService::class.java).setAction(ACTION_STOP_ALL),
				PendingIntent.FLAG_IMMUTABLE,
			)
		return Notification
			.Builder(this, CHANNEL)
			.setSmallIcon(android.R.drawable.ic_menu_manage)
			.setContentTitle(
				if (count == 0) {
					getString(R.string.helper_connected)
				} else {
					resources.getQuantityString(R.plurals.machines_running, count, count)
				},
			).setContentText(getString(if (count == 0) R.string.helper_text else R.string.notification_text))
			.setContentIntent(open)
			.addAction(Notification.Action.Builder(null, getString(R.string.stop_all), stop).build())
			.setOngoing(true)
			.build()
	}

	companion object {
		private const val CHANNEL = "machines"
		private const val NOTIFICATION_ID = 1
		private const val ACTION_STOP_ALL = "com.wonderhoylabs.minori.STOP_ALL"
		private const val ACTION_WAKE = "com.wonderhoylabs.minori.WAKE"
		private const val RECHECK_MS = 30_000L

		/** Starts, updates or stops the service to match how many machines are running, and whether a page is here. */
		fun update(context: Context) {
			val intent = Intent(context, MinoriService::class.java)
			val app = MinoriApp.get(context)
			if (app.machines.runningCount() > 0 || app.helper.inUse()) {
				runCatching { context.startForegroundService(intent) }
			} else {
				runCatching { context.startService(intent) }
			}
		}
	}
}
