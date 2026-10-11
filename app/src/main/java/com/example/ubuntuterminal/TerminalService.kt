package com.example.ubuntuterminal

import pro.sketchware.R

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/**
 * A minimal FOREGROUND service. The terminal sessions themselves live in
 * [TerminalManager] inside this same process – this service exists only to
 * hold a foreground notification so Android does not kill the process (and
 * with it every PRoot session) as soon as the user switches to another app.
 *
 * It is started whenever at least one session is alive and stopped when the
 * last one exits.
 */
class TerminalService : Service() {

    companion object {
        private const val CHANNEL_ID = "ubuntu_terminal_sessions"
        private const val NOTIFICATION_ID = 1
        private const val EXTRA_COUNT = "count"

        /** Start (or refresh) the foreground notification with [sessionCount] live sessions. */
        fun start(context: Context, sessionCount: Int) {
            val intent = Intent(context, TerminalService::class.java)
                .putExtra(EXTRA_COUNT, sessionCount)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, TerminalService::class.java))
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        // expose Android APIs (vibrate/notify/usb/…) to the container via 127.0.0.1:8037
        AndroidApiBridge.start(this)
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.ut_notif_channel),
                    NotificationManager.IMPORTANCE_LOW
                )
            )
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val count = intent?.getIntExtra(EXTRA_COUNT, 1) ?: 1
        startForeground(NOTIFICATION_ID, buildNotification(count))
        return START_NOT_STICKY
    }

    private fun buildNotification(count: Int): Notification {
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ut_ic_terminal_notify)
            .setContentTitle(getString(R.string.ut_notif_title))
            .setContentText(getString(R.string.ut_notif_text_fmt, count))
            .setContentIntent(pi)
            .setOngoing(true)
            .setShowWhen(false)
            .build()
    }
}
