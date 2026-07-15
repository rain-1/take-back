package com.takeback.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

/**
 * A minimal foreground service. Android requires an active mediaProjection-typed
 * foreground service before [android.media.projection.MediaProjection] may be
 * used for screen capture (API 29+). It holds no logic itself — starting it is
 * the permission gate; the capture runs in [RtcEngine].
 */
class ScreenCaptureService : Service() {

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val channelId = "screen_capture"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(channelId, "Screen sharing", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val notification: Notification =
            Notification.Builder(this, channelId)
                .setContentTitle("take-back")
                .setContentText("Sharing your screen")
                .setSmallIcon(android.R.drawable.ic_menu_share)
                .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(1, notification)
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
