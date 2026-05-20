package com.tx5dr.bridge

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
import android.os.PowerManager
import androidx.annotation.RequiresApi

class BridgeService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null
    private var microphoneForegroundEnabled = false
    private val statusListener: (BridgeStatus) -> Unit = {
        updateNotification()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        BridgeRuntime.addStatusListener(statusListener)
        applyWakeLock(BridgeRuntime.getPreference(BridgeRuntime.PREF_KEEP_ALIVE_ENABLED, false))
        applyForegroundServiceType(includeMicrophone = false)
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
            ACTION_STOP_BRIDGES -> {
                BridgeRuntime.stopBridges()
                setMicrophoneForeground(false)
            }
            ACTION_ENABLE_MICROPHONE_FOREGROUND -> setMicrophoneForeground(true)
            ACTION_DISABLE_MICROPHONE_FOREGROUND -> setMicrophoneForeground(false)
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
            manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, getString(R.string.app_name), NotificationManager.IMPORTANCE_LOW))
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
            .setContentTitle(getString(R.string.app_name))
            .setContentText(buildString {
                append(notificationStateLabel(status))
                if (status.webHealthy) append(" · ").append(getString(R.string.notification_service_running))
                if (keepAlive) append(" · ").append(getString(R.string.notification_keep_alive))
                if (lan != null) append(" · ").append(lan.removePrefix("http://"))
            })
            .setSmallIcon(com.tx5dr.bridge.R.drawable.ic_stat_tx5dr)
            .setContentIntent(openIntent)
            .addAction(Notification.Action.Builder(com.tx5dr.bridge.R.drawable.ic_stat_tx5dr, getString(R.string.notification_action_open), openIntent).build())
            .addAction(Notification.Action.Builder(com.tx5dr.bridge.R.drawable.ic_stat_tx5dr, getString(R.string.notification_action_stop), stopIntent).build())
            .setOngoing(true)
            .build()
    }

    private fun notificationStateLabel(status: BridgeStatus): String {
        if (!status.runtimeAbiStatus.supported) return getString(R.string.runtime_unsupported_title)
        if (status.error != null || status.runtimeState == RuntimeState.Error) return getString(R.string.runtime_needs_attention)
        if (status.serverHealthy && status.webHealthy) return getString(R.string.runtime_service_running)
        return when (status.runtimeState) {
            RuntimeState.NotInstalled -> getString(R.string.runtime_install_required)
            RuntimeState.Installing -> getString(R.string.runtime_installing)
            RuntimeState.Installed -> getString(R.string.runtime_not_started)
            RuntimeState.Starting -> getString(R.string.runtime_starting)
            RuntimeState.Running -> when {
                status.serverHealthy && status.webHealthy -> getString(R.string.runtime_service_running)
                status.serverHealthy -> getString(R.string.runtime_waiting_web)
                status.clientToolsHealthy -> getString(R.string.runtime_waiting_api)
                else -> getString(R.string.runtime_process_running)
            }
            RuntimeState.Stopping -> getString(R.string.runtime_stopping)
            RuntimeState.Stopped -> getString(R.string.runtime_stopped)
            RuntimeState.Error -> getString(R.string.runtime_needs_attention)
        }
    }

    private fun updateNotification() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification())
    }

    private fun setMicrophoneForeground(enabled: Boolean) {
        if (microphoneForegroundEnabled == enabled) {
            updateNotification()
            return
        }
        applyForegroundServiceType(includeMicrophone = enabled)
    }

    private fun applyForegroundServiceType(includeMicrophone: Boolean) {
        val notification = buildNotification()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, foregroundServiceTypeMask(includeMicrophone))
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            microphoneForegroundEnabled = includeMicrophone
            LogBus.i("Tx5drBridge", "Foreground service types updated: microphone=$includeMicrophone")
        } catch (error: SecurityException) {
            microphoneForegroundEnabled = false
            LogBus.e("Tx5drBridge", "Unable to enable microphone foreground service type", error)
            if (includeMicrophone && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, foregroundServiceTypeMask(includeMicrophone = false))
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun foregroundServiceTypeMask(includeMicrophone: Boolean): Int {
        var mask = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        mask = mask or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        if (includeMicrophone && checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            mask = mask or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        }
        return mask
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
        const val ACTION_ENABLE_MICROPHONE_FOREGROUND = "com.tx5dr.bridge.ENABLE_MICROPHONE_FOREGROUND"
        const val ACTION_DISABLE_MICROPHONE_FOREGROUND = "com.tx5dr.bridge.DISABLE_MICROPHONE_FOREGROUND"
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
