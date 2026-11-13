package com.phantom

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import com.phantom.server.Hub

class PhantomApplication : Application(), Application.ActivityLifecycleCallbacks {
    private var startedActivityCount = 0
    private val handler = Handler(Looper.getMainLooper())
    private var stopServiceRunnable: Runnable? = null

    override fun onCreate() {
        super.onCreate()
        com.phantom.log.EventLogStore.init(this)

        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            val stack = throwable.stackTraceToString().take(2000)
            com.phantom.log.EventLogStore.log("CRASH", "thread=${thread.name} err=${throwable.message}\n$stack")
            defaultHandler?.uncaughtException(thread, throwable)
        }

        registerActivityLifecycleCallbacks(this)
    }

    private var appStartLogged = false

    // Only TV screens keep the server alive; the phone remote (RemoteActivity) never starts it.
    private fun isTvActivity(a: Activity) =
        a is BrowserActivity || a is com.phantom.player.NativePlayerActivity

    override fun onActivityStarted(activity: Activity) {
        if (!isTvActivity(activity)) return
        com.phantom.pairing.PairPrompt.attach(activity)
        if (!appStartLogged) {
            appStartLogged = true
            com.phantom.log.EventLogStore.log("APP_START", "first TV activity started")
        }
        stopServiceRunnable?.let { handler.removeCallbacks(it) }
        stopServiceRunnable = null

        if (startedActivityCount == 0) {
            Hub.isActivityForeground = true
            startService(Intent(this, CoreService::class.java))
        }
        startedActivityCount++
    }

    override fun onActivityStopped(activity: Activity) {
        if (!isTvActivity(activity)) return
        com.phantom.pairing.PairPrompt.detach(activity)
        startedActivityCount--
        if (startedActivityCount <= 0) {
            startedActivityCount = 0
            val r = Runnable {
                if (startedActivityCount == 0) {
                    Hub.isActivityForeground = false
                    stopService(Intent(this, CoreService::class.java))
                }
            }
            stopServiceRunnable = r
            handler.postDelayed(r, 1000L)
        }
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
    override fun onActivityResumed(activity: Activity) {}
    override fun onActivityPaused(activity: Activity) {}
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
    override fun onActivityDestroyed(activity: Activity) {}
}
