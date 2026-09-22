package com.takeback.app

import android.app.Application
import com.takeback.app.net.ApiClient
import com.takeback.app.net.Events

/**
 * Application entry point. Its whole job is to initialize app-wide singletons
 * before ANY activity runs.
 *
 * ApiClient (a singleton) used to be init'd only in LoginActivity. During a
 * memory-heavy video call the OS often kills the app process; when you then
 * pressed back, Android recreated a different activity (e.g. the chat you
 * returned to) WITHOUT going through LoginActivity, so ApiClient's lateinit
 * fields were unset and the app crashed out to the login screen — looking like
 * you'd been logged out even though the session cookie was still saved.
 *
 * Application.onCreate runs first in every restart path, so initializing here
 * makes the session survive process death.
 */
class TakeBackApp : Application() {
    override fun onCreate() {
        super.onCreate()
        ApiClient.init(this)
        Palette.init(this)
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        val stopEventsWhenBackgrounded = Runnable {
            if (Events.startedActivities == 0) Events.stop()
        }
        // Events needs to know whether the app is in the foreground, to decide how
        // soon leaving a server's screens counts as no longer viewing it. The
        // socket is a foreground-only fast path now; WorkManager handles closed-app
        // checks without keeping a process or connection alive indefinitely.
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(a: android.app.Activity) {
                main.removeCallbacks(stopEventsWhenBackgrounded)
                Events.startedActivities++
                if (ApiClient.hasSession()) Events.start(applicationContext)
            }
            override fun onActivityStopped(a: android.app.Activity) {
                Events.startedActivities--
                if (Events.startedActivities == 0) {
                    // Activity-to-activity navigation briefly reaches zero too.
                    main.postDelayed(stopEventsWhenBackgrounded, 1_000)
                }
            }
            override fun onActivityCreated(a: android.app.Activity, b: android.os.Bundle?) {}
            override fun onActivityResumed(a: android.app.Activity) {}
            override fun onActivityPaused(a: android.app.Activity) {}
            override fun onActivitySaveInstanceState(a: android.app.Activity, b: android.os.Bundle) {}
            override fun onActivityDestroyed(a: android.app.Activity) {}
        })
    }
}
