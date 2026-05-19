package com.tx5dr.bridge

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.webkit.JavascriptInterface
import android.webkit.WebView
import org.json.JSONObject
import java.lang.ref.WeakReference
import java.util.concurrent.CopyOnWriteArrayList

class AndroidWebNotificationBridge(
    private val activity: MainActivity,
    webView: WebView,
    initialUrl: String,
) {
    private val webViewRef = WeakReference(webView)
    private val pendingPermissionRequestIds = CopyOnWriteArrayList<String>()
    @Volatile private var trustedWebView = isTrustedUrl(initialUrl)

    init {
        createEventChannel(activity)
    }

    @JavascriptInterface
    fun getPermission(): String {
        if (!isTrustedWebView()) return PERMISSION_DENIED
        return readPermissionState(activity)
    }

    @JavascriptInterface
    fun requestPermission(requestId: String?): String {
        if (!isTrustedWebView()) return PERMISSION_DENIED
        val normalizedRequestId = sanitizeRequestId(requestId)
        val current = readPermissionState(activity)
        if (current == PERMISSION_GRANTED || current == PERMISSION_DENIED) {
            normalizedRequestId?.let { dispatchPermissionResult(it, current) }
            return current
        }

        if (Build.VERSION.SDK_INT < 33) {
            normalizedRequestId?.let { dispatchPermissionResult(it, current) }
            return current
        }

        if (normalizedRequestId != null) {
            pendingPermissionRequestIds.add(normalizedRequestId)
        }

        activity.runOnUiThread {
            NotificationPermissionState.markRequested(activity)
            activity.requestPermissions(
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                MainActivity.REQ_POST_NOTIFICATIONS
            )
        }
        return PERMISSION_DEFAULT
    }

    @JavascriptInterface
    fun showNotification(payloadJson: String?): Boolean {
        if (!isTrustedWebView()) return false
        val payload = parsePayload(payloadJson) ?: return false
        if (readPermissionState(activity) != PERMISSION_GRANTED) return false

        return runCatching {
            createEventChannel(activity)
            val openIntent = PendingIntent.getActivity(
                activity,
                payload.tag.hashCode(),
                Intent(activity, MainActivity::class.java)
                    .setAction(MainActivity.ACTION_OPEN_WEBVIEW)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                pendingIntentFlags()
            )
            val notification = notificationBuilder(activity)
                .setContentTitle(payload.title)
                .setContentText(payload.body)
                .setStyle(Notification.BigTextStyle().bigText(payload.body))
                .setSmallIcon(com.tx5dr.bridge.R.drawable.ic_stat_tx5dr)
                .setContentIntent(openIntent)
                .setAutoCancel(true)
                .setShowWhen(true)
                .build()
            val manager = activity.getSystemService(NotificationManager::class.java)
            manager.notify(payload.tag.takeIf { it.isNotBlank() }, EVENTS_NOTIFICATION_ID, notification)
            true
        }.onFailure { error ->
            LogBus.w("WebNotification", "Failed to show native notification: ${error.message}")
        }.getOrDefault(false)
    }

    @JavascriptInterface
    fun openSettings() {
        if (!isTrustedWebView()) return
        activity.runOnUiThread {
            val intent = if (Build.VERSION.SDK_INT >= 26) {
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, activity.packageName)
            } else {
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${activity.packageName}"))
            }
            runCatching { activity.startActivity(intent) }.onFailure {
                activity.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${activity.packageName}")))
            }
        }
    }

    fun onPermissionResult() {
        val result = readPermissionState(activity)
        pendingPermissionRequestIds.toList().forEach { dispatchPermissionResult(it, result) }
        pendingPermissionRequestIds.clear()
    }

    fun updateUrl(url: String?) {
        trustedWebView = isTrustedUrl(url)
    }

    private fun dispatchPermissionResult(requestId: String, permission: String) {
        val js = "window.__tx5drAndroidNotificationPermissionResult && " +
            "window.__tx5drAndroidNotificationPermissionResult(${JSONObject.quote(requestId)}, ${JSONObject.quote(permission)})"
        activity.runOnUiThread {
            webViewRef.get()?.evaluateJavascript(js, null)
        }
    }


    private fun isTrustedWebView(): Boolean {
        return trustedWebView
    }

    private fun parsePayload(payloadJson: String?): NotificationPayload? {
        if (payloadJson.isNullOrBlank()) return null
        return runCatching {
            val json = JSONObject(payloadJson)
            val title = sanitizeText(json.optString("title", activity.getString(R.string.app_name)), MAX_TITLE_LENGTH)
                .ifBlank { activity.getString(R.string.app_name) }
            val body = sanitizeText(json.optString("body", ""), MAX_BODY_LENGTH)
            val tag = sanitizeTag(json.optString("tag", "tx5dr-event"))
            NotificationPayload(title = title, body = body, tag = tag)
        }.getOrNull()
    }

    private data class NotificationPayload(
        val title: String,
        val body: String,
        val tag: String,
    )

    companion object {
        private const val CHANNEL_ID = "tx5dr_events"
        private const val EVENTS_NOTIFICATION_ID = 2001
        private const val MAX_TITLE_LENGTH = 80
        private const val MAX_BODY_LENGTH = 240
        private const val MAX_TAG_LENGTH = 80
        private const val PERMISSION_GRANTED = "granted"
        private const val PERMISSION_DENIED = "denied"
        private const val PERMISSION_DEFAULT = "default"

        private fun isTrustedUrl(url: String?): Boolean {
            val uri = runCatching { Uri.parse(url ?: return false) }.getOrNull() ?: return false
            return uri.scheme == "http" && uri.host == "127.0.0.1" && uri.port == 8076
        }

        fun readPermissionState(context: Context): String {
            val manager = context.getSystemService(NotificationManager::class.java)
            val enabled = if (Build.VERSION.SDK_INT >= 24) manager.areNotificationsEnabled() else true
            if (!enabled) return PERMISSION_DENIED

            if (Build.VERSION.SDK_INT < 33) return PERMISSION_GRANTED

            if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
                return PERMISSION_GRANTED
            }

            return if (NotificationPermissionState.wasRequested(context)) PERMISSION_DENIED else PERMISSION_DEFAULT
        }

        private fun createEventChannel(context: Context) {
            if (Build.VERSION.SDK_INT < 26) return
            val manager = context.getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.notification_events_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = context.getString(R.string.notification_events_channel_description)
            }
            manager.createNotificationChannel(channel)
        }

        private fun notificationBuilder(context: Context): Notification.Builder {
            return if (Build.VERSION.SDK_INT >= 26) Notification.Builder(context, CHANNEL_ID) else Notification.Builder(context)
        }

        private fun pendingIntentFlags(): Int {
            return PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        }

        private fun sanitizeText(value: String, maxLength: Int): String {
            return value
                .replace(Regex("[\\u0000-\\u001F\\u007F]"), " ")
                .replace(Regex("\\s+"), " ")
                .trim()
                .take(maxLength)
        }

        private fun sanitizeTag(value: String): String {
            return sanitizeText(value, MAX_TAG_LENGTH).replace(Regex("[^A-Za-z0-9_.:-]"), "_").ifBlank { "tx5dr-event" }
        }

        private fun sanitizeRequestId(value: String?): String? {
            return value?.take(MAX_TAG_LENGTH)?.takeIf { it.matches(Regex("[A-Za-z0-9_.:-]+")) }
        }
    }
}

private object NotificationPermissionState {
    private const val PREFS = "tx5dr_notification_permissions"
    private const val KEY_REQUESTED = "post_notifications_requested"

    fun wasRequested(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_REQUESTED, false)

    fun markRequested(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_REQUESTED, true)
            .apply()
    }
}
