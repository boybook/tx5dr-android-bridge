package com.tx5dr.bridge

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject

class WebViewAudioRouteManager {
    private val activeReasons = linkedSetOf<String>()
    private var originalMode: Int? = null
    private var originalSpeakerphoneOn: Boolean? = null
    private var routeAcquired = false

    @Synchronized
    fun enter(context: Context, rawReason: String?): String {
        val reason = normalizeReason(rawReason)
        if (activeReasons.isNotEmpty()) {
            activeReasons.add(reason)
            return statusJson(RouteResult(true, "already-active")).toString()
        }
        val result = activate(context.applicationContext, reason)
        if (result.ok) activeReasons.add(reason)
        return statusJson(result).toString()
    }

    @Synchronized
    fun leave(context: Context, rawReason: String?): String {
        val reason = normalizeReason(rawReason)
        activeReasons.remove(reason)
        val result = if (activeReasons.isEmpty()) deactivate(context.applicationContext, reason) else RouteResult(true, "still-active")
        return statusJson(result).toString()
    }

    @Synchronized
    fun leaveAll(context: Context, rawReason: String? = "webview-destroyed"): String {
        val reason = normalizeReason(rawReason)
        activeReasons.clear()
        val result = deactivate(context.applicationContext, reason)
        return statusJson(result).toString()
    }

    @Synchronized
    fun probe(context: Context): String {
        val manager = context.applicationContext.getSystemService(AudioManager::class.java)
        val currentDevice = if (Build.VERSION.SDK_INT >= 31) manager?.communicationDevice else null
        return statusJson(RouteResult(true, "probe"), currentDevice).toString()
    }

    private fun activate(context: Context, reason: String): RouteResult {
        val manager = context.getSystemService(AudioManager::class.java)
            ?: return RouteResult(false, "audio-manager-unavailable")
        val savedMode = manager.mode
        @Suppress("DEPRECATION")
        val savedSpeakerphoneOn = manager.isSpeakerphoneOn

        return if (Build.VERSION.SDK_INT >= 31) {
            val speaker = findBuiltinSpeaker(manager)
                ?: return RouteResult(false, "builtin-speaker-unavailable")
            @Suppress("DEPRECATION")
            manager.mode = AudioManager.MODE_IN_COMMUNICATION
            val accepted = runCatching { manager.setCommunicationDevice(speaker) }
                .onFailure { LogBus.w(TAG, "Failed to request WebView speaker route: ${it.message}") }
                .getOrDefault(false)
            if (accepted) {
                originalMode = savedMode
                originalSpeakerphoneOn = savedSpeakerphoneOn
                routeAcquired = true
                LogBus.i(TAG, "WebView audio routed to phone speaker reason=$reason device=${speaker.describe()}")
                RouteResult(true, "communication-device", speaker)
            } else {
                restore(manager, savedMode, savedSpeakerphoneOn, clearCommunicationDevice = true)
                LogBus.w(TAG, "Android rejected WebView phone speaker route reason=$reason device=${speaker.describe()}")
                RouteResult(false, "communication-device-rejected", speaker)
            }
        } else {
            @Suppress("DEPRECATION")
            manager.mode = AudioManager.MODE_IN_COMMUNICATION
            @Suppress("DEPRECATION")
            manager.isSpeakerphoneOn = true
            originalMode = savedMode
            originalSpeakerphoneOn = savedSpeakerphoneOn
            routeAcquired = true
            LogBus.i(TAG, "WebView audio routed with legacy speakerphone reason=$reason")
            RouteResult(true, "legacy-speakerphone")
        }
    }

    private fun deactivate(context: Context, reason: String): RouteResult {
        if (!routeAcquired) return RouteResult(true, "inactive")
        val manager = context.getSystemService(AudioManager::class.java)
            ?: return RouteResult(false, "audio-manager-unavailable")
        restore(manager, originalMode, originalSpeakerphoneOn, clearCommunicationDevice = Build.VERSION.SDK_INT >= 31)
        routeAcquired = false
        originalMode = null
        originalSpeakerphoneOn = null
        LogBus.i(TAG, "WebView audio route restored reason=$reason")
        return RouteResult(true, "restored")
    }

    private fun restore(
        manager: AudioManager,
        savedMode: Int?,
        savedSpeakerphoneOn: Boolean?,
        clearCommunicationDevice: Boolean,
    ) {
        if (Build.VERSION.SDK_INT >= 31 && clearCommunicationDevice) {
            runCatching { manager.clearCommunicationDevice() }
                .onFailure { LogBus.w(TAG, "Failed to clear WebView communication device: ${it.message}") }
        } else {
            savedSpeakerphoneOn?.let { wasOn ->
                @Suppress("DEPRECATION")
                manager.isSpeakerphoneOn = wasOn
            }
        }
        savedMode?.let { mode ->
            @Suppress("DEPRECATION")
            manager.mode = mode
        }
    }

    private fun findBuiltinSpeaker(manager: AudioManager): AudioDeviceInfo? {
        if (Build.VERSION.SDK_INT < 31) return null
        return manager.availableCommunicationDevices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
            ?: manager.availableCommunicationDevices.firstOrNull {
                Build.VERSION.SDK_INT >= 31 && it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE
            }
    }

    private fun statusJson(result: RouteResult, currentDevice: AudioDeviceInfo? = result.device): JSONObject =
        JSONObject()
            .put("ok", result.ok)
            .put("reason", result.reason)
            .put("active", activeReasons.isNotEmpty())
            .put("activeReasons", JSONArray(activeReasons.toList()))
            .put("sdk", Build.VERSION.SDK_INT)
            .put("route", currentDevice?.describe() ?: JSONObject.NULL)

    private fun normalizeReason(reason: String?): String =
        reason?.trim()?.takeIf { it.isNotEmpty() }?.take(64) ?: "webview-audio"

    private fun AudioDeviceInfo.describe(): String =
        "type=$type,id=$id,name=${productName ?: "unknown"}"

    private data class RouteResult(
        val ok: Boolean,
        val reason: String,
        val device: AudioDeviceInfo? = null,
    )

    companion object {
        private const val TAG = "WebViewAudioRoute"
    }
}
