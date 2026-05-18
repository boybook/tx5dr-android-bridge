package com.tx5dr.bridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder

class BridgeService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(1, buildNotification("TX-5DR Bridge ready"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        LogBus.i("Tx5drBridge", "BridgeService action: ${intent?.action ?: "none"}")
        when (intent?.action) {
            ACTION_START_RUNTIME -> BridgeRuntime.start()
            ACTION_STOP_RUNTIME -> BridgeRuntime.stop()
            ACTION_INSTALL -> BridgeRuntime.installOrUpdate()
        }
        return START_STICKY
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "TX-5DR Bridge", NotificationManager.IMPORTANCE_LOW))
        }
    }

    private fun buildNotification(text: String): Notification {
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL_ID) else Notification.Builder(this)
        return builder
            .setContentTitle("TX-5DR Bridge")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "tx5dr_bridge"
        const val ACTION_START_RUNTIME = "com.tx5dr.bridge.START_RUNTIME"
        const val ACTION_STOP_RUNTIME = "com.tx5dr.bridge.STOP_RUNTIME"
        const val ACTION_INSTALL = "com.tx5dr.bridge.INSTALL"

        fun start(context: Context, action: String) {
            val intent = Intent(context, BridgeService::class.java).setAction(action)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent) else context.startService(intent)
        }
    }
}
