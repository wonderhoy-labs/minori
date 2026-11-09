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
import android.os.IBinder
import com.wonderhoylabs.minori.R
import com.wonderhoylabs.minori.MinoriApp
import com.wonderhoylabs.minori.ui.MainActivity

/**
 * Keeps Minori's process alive while machines run. An AVF VM belongs to the process that started it, so without a
 * foreground service Android could stop Minori, and every VM with it, as soon as the app left the screen.
 */
class MinoriService : Service() {
	override fun onBind(intent: Intent?): IBinder? = null

	override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
		val machines = MinoriApp.get(this).machines
		if (intent?.action == ACTION_STOP_ALL) {
			machines.stopAll()
		}
		val count = machines.runningCount()
		if (count == 0) {
			stopForeground(STOP_FOREGROUND_REMOVE)
			stopSelf()
			return START_NOT_STICKY
		}
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
			.setContentTitle(resources.getQuantityString(R.plurals.machines_running, count, count))
			.setContentText(getString(R.string.notification_text))
			.setContentIntent(open)
			.addAction(Notification.Action.Builder(null, getString(R.string.stop_all), stop).build())
			.setOngoing(true)
			.build()
	}

	companion object {
		private const val CHANNEL = "machines"
		private const val NOTIFICATION_ID = 1
		private const val ACTION_STOP_ALL = "com.wonderhoylabs.minori.STOP_ALL"

		/** Starts, updates or stops the service to match how many machines are running. */
		fun update(context: Context) {
			val intent = Intent(context, MinoriService::class.java)
			if (MinoriApp.get(context).machines.runningCount() > 0) {
				runCatching { context.startForegroundService(intent) }
			} else {
				runCatching { context.startService(intent) }
			}
		}
	}
}
