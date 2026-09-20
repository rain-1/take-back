package com.takeback.app

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.content.ContextCompat

/**
 * Keeps a call alive while you're doing something else.
 *
 * Android suspends an app the moment it leaves the screen unless it holds a
 * foreground service — no amount of "allow background activity" in system
 * settings changes that. Without this, leaving the app or locking the phone
 * killed the call's audio (reported by @Etheri on a Nothing Phone 1).
 *
 * The service type has to match what the call is actually using, or Android 14
 * refuses to start it: microphone while the mic is live, camera as well when
 * video is on, and plain media playback for a listen-only call.
 */
class CallService : Service() {

    companion object {
        private const val CHANNEL = "takeback_call"
        private const val ID = 7

        /** Start (or update) the ongoing-call notification. */
        fun start(ctx: Context, title: String) {
            ContextCompat.startForegroundService(
                ctx, Intent(ctx, CallService::class.java).putExtra("title", title))
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, CallService::class.java))
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val title = intent?.getStringExtra("title") ?: "In a call"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, "Calls", NotificationManager.IMPORTANCE_LOW))
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification: Notification = Notification.Builder(this, CHANNEL)
            .setContentTitle(title)
            .setContentText("Tap to come back to the call")
            .setSmallIcon(android.R.drawable.stat_sys_speakerphone)
            .setOngoing(true)
            .setContentIntent(open)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(ID, notification, serviceTypes())
        } else {
            startForeground(ID, notification)
        }
        return START_NOT_STICKY
    }

    /**
     * What this call is using. Declaring a type we don't hold the permission for
     * makes Android 14 throw instead of starting the service, so each one is
     * checked.
     */
    private fun serviceTypes(): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return 0
        var types = 0
        if (granted(Manifest.permission.RECORD_AUDIO)) {
            types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        }
        if (granted(Manifest.permission.CAMERA)) {
            types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        }
        // Listening in with neither: it's still playing the call's audio.
        if (types == 0 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            types = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        }
        return types
    }

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    override fun onBind(intent: Intent?): IBinder? = null
}
