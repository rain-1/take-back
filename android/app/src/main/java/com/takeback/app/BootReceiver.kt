package com.takeback.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * After a reboot, start listening again — otherwise messages wouldn't reach you
 * until you next opened the app.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        ApiClientInit(context)
        ConnectionService.start(context)
    }

    private fun ApiClientInit(context: Context) {
        com.takeback.app.net.ApiClient.init(context.applicationContext)
    }
}
