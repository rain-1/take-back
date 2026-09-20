package com.takeback.app

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
import androidx.core.content.ContextCompat
import com.takeback.app.net.ApiClient
import com.takeback.app.net.Events

/**
 * Stays connected while the app is closed, so a message reaches you.
 *
 * take-back has no push service behind it: messages arrive over the app's own
 * connection to your server. Android suspends an app that isn't in front, which
 * is why a DM only ever arrived while the app was open. A foreground service
 * keeps that connection — the cost is the quiet notification Android requires
 * in return, and you can turn it off in Settings.
 */
class ConnectionService : Service() {

    companion object {
        private const val CHANNEL = "takeback_connection"
        private const val ID = 9
        private const val PREFS = "tb_config"
        private const val KEY = "stay_connected"

        /** Whether to hold a connection open while the app is closed. */
        fun enabled(ctx: Context): Boolean =
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, true)

        fun setEnabled(ctx: Context, on: Boolean) {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY, on).apply()
            if (on) start(ctx) else stop(ctx)
        }

        /** Start it if it's wanted and there's an account to listen for. */
        fun start(ctx: Context) {
            if (!enabled(ctx) || !ApiClient.hasSession()) return
            runCatching {
                ContextCompat.startForegroundService(ctx, Intent(ctx, ConnectionService::class.java))
            }
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, ConnectionService::class.java))
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                // MIN: it belongs in the shade, not in your face.
                NotificationChannel(CHANNEL, "Staying connected", NotificationManager.IMPORTANCE_MIN))
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, HomeActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification: Notification = Notification.Builder(this, CHANNEL)
            .setContentTitle("take-back")
            .setContentText("Connected — messages will reach you")
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setOngoing(true)
            .setContentIntent(open)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(ID, notification)
        }
        // The connection itself lives in Events, which fans messages out to any
        // screen that happens to be open and raises notifications for the rest.
        Events.start(applicationContext)
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
