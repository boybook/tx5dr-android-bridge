package com.tx5dr.bridge

import android.app.Application
import android.os.Looper
import android.util.Log

class BridgeApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        installBackgroundCrashGuard()
        BridgeRuntime.init(this)
    }

    private fun installBackgroundCrashGuard() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            if (thread == Looper.getMainLooper().thread) {
                previous?.uncaughtException(thread, error)
                return@setDefaultUncaughtExceptionHandler
            }
            Log.e("Tx5drBridge", "Background thread ${thread.name} failed", error)
            runCatching { LogBus.e("Tx5drBridge", "Background thread ${thread.name} failed", error) }
        }
    }
}
