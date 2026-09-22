package com.takeback.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Explicit debug-only hook for exercising the same wake path a push receiver uses. */
class BackgroundSyncTestReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        BackgroundNotifications.wake(context)
    }
}
