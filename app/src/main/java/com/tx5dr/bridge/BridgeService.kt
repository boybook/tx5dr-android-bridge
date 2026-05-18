package com.tx5dr.bridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager

class BridgeService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null
    private val statusListener: (BridgeStatus) -> Unit = {
        updateNotification()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        BridgeRuntime.addStatusListener(statusListener)
        applyWakeLock(BridgeRuntime.getPreference(BridgeRuntime.PREF_KEEP_ALIVE_ENABLED, false))
        startForeground(NOTIFICATION_ID, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        LogBus.i("Tx5drBridge", "BridgeService action: ${intent?.action ?: "none"}")
        when (intent?.action) {
            ACTION_BOOTSTRAP -> BridgeRuntime.bootstrap()
            ACTION_START_RUNTIME -> BridgeRuntime.start()
            ACTION_STOP_RUNTIME -> {
                BridgeRuntime.stop()
                BridgeRuntime.setPreference(BridgeRuntime.PREF_KEEP_ALIVE_ENABLED, false)
                applyWakeLock(false)
            }
            ACTION_INSTALL -> BridgeRuntime.installOrUpdate()
            ACTION_START_BRIDGES -> BridgeRuntime.startBridges()
            ACTION_STOP_BRIDGES -> BridgeRuntime.stopBridges()
            ACTION_SET_KEEPALIVE -> {
                val enabled = intent.getBooleanExtra(EXTRA_ENABLED, false)
                BridgeRuntime.setPreference(BridgeRuntime.PREF_KEEP_ALIVE_ENABLED, enabled)
                applyWakeLock(enabled)
            }
        }
        updateNotification()
        return START_STICKY
    }

    override fun onDestroy() {
        BridgeRuntime.removeStatusListener(statusListener)
        releaseWakeLock()
        super.onDestroy()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "TX-5DR Bridge", NotificationManager.IMPORTANCE_LOW))
        }
    }

    private fun buildNotification(): Notification {
        val status = BridgeRuntime.snapshotStatus()
        val lan = BridgeRuntime.refreshNetworkAccess().urls.firstOrNull()
        val keepAlive = BridgeRuntime.getPreference(BridgeRuntime.PREF_KEEP_ALIVE_ENABLED, false)
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            pendingIntentFlags()
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, BridgeService::class.java).setAction(ACTION_STOP_RUNTIME),
            pendingIntentFlags()
        )
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL_ID) else Notification.Builder(this)
        return builder
            .setContentTitle("TX-5DR Bridge")
            .setContentText(buildString {
                append(status.runtimeState)
                if (status.webHealthy) append(" · Web ready")
                if (keepAlive) append(" · 值守中")
                if (lan != null) append(" · ").append(lan.removePrefix("http://"))
            })
            .setSmallIcon(com.tx5dr.bridge.R.drawable.ic_stat_tx5dr)
            .setContentIntent(openIntent)
            .addAction(Notification.Action.Builder(com.tx5dr.bridge.R.drawable.ic_stat_tx5dr, "打开", openIntent).build())
            .addAction(Notification.Action.Builder(com.tx5dr.bridge.R.drawable.ic_stat_tx5dr, "停止", stopIntent).build())
            .setOngoing(true)
            .build()
    }

    private fun updateNotification() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification())
    }

    private fun applyWakeLock(enabled: Boolean) {
        if (!enabled) {
            releaseWakeLock()
            return
        }
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TX5DR:BridgeKeepAlive").apply {
            setReferenceCounted(false)
            acquire()
        }
        LogBus.i("Tx5drBridge", "Keep-alive WakeLock acquired")
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.takeIf { it.isHeld }?.release()
            if (wakeLock != null) LogBus.i("Tx5drBridge", "Keep-alive WakeLock released")
        } catch (_: Throwable) {
        } finally {
            wakeLock = null
        }
    }

    private fun pendingIntentFlags(): Int {
        return PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
    }

    companion object {
        private const val CHANNEL_ID = "tx5dr_bridge"
        private const val NOTIFICATION_ID = 1
        const val ACTION_BOOTSTRAP = "com.tx5dr.bridge.BOOTSTRAP"
        const val ACTION_START_RUNTIME = "com.tx5dr.bridge.START_RUNTIME"
        const val ACTION_STOP_RUNTIME = "com.tx5dr.bridge.STOP_RUNTIME"
        const val ACTION_INSTALL = "com.tx5dr.bridge.INSTALL"
        const val ACTION_START_BRIDGES = "com.tx5dr.bridge.START_BRIDGES"
        const val ACTION_STOP_BRIDGES = "com.tx5dr.bridge.STOP_BRIDGES"
        const val ACTION_SET_KEEPALIVE = "com.tx5dr.bridge.SET_KEEPALIVE"
        const val EXTRA_ENABLED = "enabled"

        fun start(context: Context, action: String) {
            val intent = Intent(context, BridgeService::class.java).setAction(action)
            start(context, intent)
        }

        fun start(context: Context, intent: Intent) {
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent) else context.startService(intent)
        }
    }
}
