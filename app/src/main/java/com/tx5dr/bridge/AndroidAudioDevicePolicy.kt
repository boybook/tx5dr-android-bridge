package com.tx5dr.bridge

import java.security.MessageDigest
import java.util.Locale

internal fun isCurrentAudioSessionGeneration(expected: Long, current: Long?): Boolean = current == expected

/** Android audio device type values, kept here so routing policy remains a plain JVM-testable unit. */
internal object AndroidAudioDeviceType {
    const val BUILTIN_SPEAKER = 2
    const val WIRED_HEADSET = 3
    const val WIRED_HEADPHONES = 4
    const val LINE_ANALOG = 5
    const val USB_DEVICE = 11
    const val BUILTIN_MIC = 15
    const val AUX_LINE = 19
    const val USB_HEADSET = 22
    const val BUILTIN_SPEAKER_SAFE = 24
}

object AudioRoute {
    const val USB = "usb"
    const val WIRED_HEADSET = "wired-headset"
    const val WIRED_HEADPHONES = "wired-headphones"
    const val ANALOG_LINE = "analog-line"
    const val BUILTIN_MIC = "builtin-mic"
    const val BUILTIN_SPEAKER = "builtin-speaker"
}

object AudioRouteState {
    const val IDLE = "idle"
    const val OPENING = "opening"
    const val VERIFIED = "verified"
    const val LOST = "lost"
    const val UNAVAILABLE = "unavailable"
    const val FAILED = "failed"
}

internal data class AndroidAudioDeviceDescriptor(
    val androidDeviceId: Int,
    val type: Int,
    val isSource: Boolean,
    val isSink: Boolean,
    val direction: String,
    val productName: String,
    val address: String = "",
    val sampleRates: List<Int> = emptyList(),
    val channelCounts: List<Int> = emptyList(),
)

internal enum class AndroidAudioCaptureProfile {
    UNPROCESSED,
    VOICE_RECOGNITION,
    DEFAULT,
}

internal object AndroidAudioDevicePolicy {
    private val preferredRates = listOf(48_000, 44_100)

    fun classify(descriptor: AndroidAudioDeviceDescriptor): AndroidAudioEndpoint? {
        val kind = kindFor(descriptor.type) ?: return null
        val supportsDirection = when (descriptor.direction) {
            "input" -> descriptor.isSource && descriptor.type in inputTypes
            "output" -> descriptor.isSink && descriptor.type in outputTypes
            else -> false
        }
        if (!supportsDirection) return null

        val transport = when (kind) {
            AudioRoute.USB -> "usb"
            AudioRoute.WIRED_HEADSET, AudioRoute.WIRED_HEADPHONES, AudioRoute.ANALOG_LINE -> "analog"
            else -> "builtin"
        }
        val connector = when (kind) {
            AudioRoute.WIRED_HEADSET, AudioRoute.WIRED_HEADPHONES, AudioRoute.ANALOG_LINE -> "3.5mm"
            AudioRoute.USB -> "usb"
            AudioRoute.BUILTIN_MIC, AudioRoute.BUILTIN_SPEAKER -> "builtin"
            else -> "unknown"
        }
        val routeKey = baseRouteKey(descriptor.direction, kind, descriptor.address, descriptor.productName)
        return AndroidAudioEndpoint(
            id = descriptor.androidDeviceId,
            manifestId = manifestId(descriptor.direction, kind, descriptor.androidDeviceId, routeKey),
            routeKey = routeKey,
            direction = descriptor.direction,
            kind = kind,
            transport = transport,
            connector = connector,
            type = descriptor.type,
            name = descriptor.productName,
            address = descriptor.address.takeIf { it.isNotBlank() },
            sampleRates = descriptor.sampleRates,
            channelCounts = descriptor.channelCounts,
            socketPath = socketPath(descriptor.direction, kind, descriptor.androidDeviceId, routeKey),
        )
    }

    fun captureProfiles(kind: String): List<AndroidAudioCaptureProfile> = when (kind) {
        AudioRoute.WIRED_HEADSET, AudioRoute.ANALOG_LINE, AudioRoute.BUILTIN_MIC -> listOf(
            AndroidAudioCaptureProfile.UNPROCESSED,
            AndroidAudioCaptureProfile.VOICE_RECOGNITION,
            AndroidAudioCaptureProfile.DEFAULT,
        )
        else -> listOf(AndroidAudioCaptureProfile.DEFAULT)
    }

    fun defaultEndpoint(devices: List<AndroidAudioEndpoint>, direction: String): AndroidAudioEndpoint? {
        val available = devices.filter { it.direction == direction && it.available }
        val builtinKind = if (direction == "input") AudioRoute.BUILTIN_MIC else AudioRoute.BUILTIN_SPEAKER
        return available.firstOrNull { it.kind == AudioRoute.USB }
            ?: available.firstOrNull { it.kind == builtinKind }
            ?: available.firstOrNull()
    }

    fun makeIdentitiesUnique(devices: List<AndroidAudioEndpoint>): List<AndroidAudioEndpoint> {
        // A blank-address duplicate cannot be distinguished stably across hotplug; expose one logical route.
        return devices.sortedWith(compareBy<AndroidAudioEndpoint> { it.routeKey }.thenBy { it.id })
            .distinctBy { it.routeKey }
    }

    fun mergeEndpointState(
        direction: String,
        detected: List<AndroidAudioEndpoint>,
        previous: List<AndroidAudioEndpoint>,
    ): List<AndroidAudioEndpoint> {
        val previousByRoute = previous.associateBy { it.routeKey }
        val active = detected.map { endpoint ->
            val old = previousByRoute[endpoint.routeKey]
            val restored = old != null && old.id == endpoint.id && old.routeState != AudioRouteState.UNAVAILABLE
            endpoint.copy(
                clientConnected = restored && old?.clientConnected == true,
                routeState = if (restored) old?.routeState ?: AudioRouteState.IDLE else AudioRouteState.IDLE,
                routeVerified = restored && old?.routeVerified == true,
                failureReason = if (restored) old?.failureReason else null,
                audioSource = if (restored) old?.audioSource else null,
            )
        }
        val activeKeys = active.mapTo(mutableSetOf()) { it.routeKey }
        val unavailable = previous.filter {
            it.direction == direction && it.routeKey !in activeKeys && it.kind != AudioRoute.USB
        }.map {
            it.copy(
                available = false,
                clientConnected = false,
                routeState = AudioRouteState.UNAVAILABLE,
                routeVerified = false,
                failureReason = "Physical audio endpoint is disconnected",
            )
        }
        return active + unavailable
    }

    fun resetReleasedEndpoint(endpoint: AndroidAudioEndpoint): AndroidAudioEndpoint = if (endpoint.available) {
        endpoint.copy(
            clientConnected = false,
            routeState = AudioRouteState.IDLE,
            routeVerified = false,
            failureReason = null,
            audioSource = null,
        )
    } else endpoint

    fun manifestFields(device: AndroidAudioEndpoint, isDefault: Boolean): Map<String, Any?> = linkedMapOf(
        "id" to device.manifestId,
        "androidDeviceId" to device.id,
        "routeKey" to device.routeKey,
        "name" to device.name,
        "direction" to device.direction,
        "kind" to device.kind,
        "transport" to device.transport,
        "connector" to device.connector,
        "address" to device.address,
        "type" to device.type,
        "socketPath" to device.socketPath,
        "available" to device.available,
        "clientConnected" to device.clientConnected,
        "connected" to device.clientConnected,
        "routeState" to device.routeState,
        "routeVerified" to device.routeVerified,
        "failureReason" to device.failureReason,
        "audioSource" to device.audioSource,
        "isDefault" to isDefault,
    )

    fun preferredSampleRate(rates: List<Int>): Int =
        preferredRates.firstOrNull { it in rates } ?: rates.firstOrNull() ?: 48_000

    fun capabilityFields(device: AndroidAudioEndpoint): Map<String, Any?> = linkedMapOf(
        "sampleRates" to device.sampleRates.ifEmpty { preferredRates },
        "channelCounts" to listOf(1),
        "sampleFormats" to if (device.direction == "output") listOf("s16le", "f32le") else listOf("s16le"),
        "sampleRateConfigurable" to (device.direction == "output"),
        "fixedSampleRate" to if (device.direction == "input") preferredSampleRate(device.sampleRates) else null,
        "sampleFormatConfigurable" to (device.direction == "output"),
        "bufferSizeConfigurable" to false,
        "channelModeConfigurable" to false,
        "outputRouteAck" to (device.direction == "output"),
        "fixedChannelCount" to 1,
    )

    private fun kindFor(type: Int): String? = when (type) {
        AndroidAudioDeviceType.USB_DEVICE, AndroidAudioDeviceType.USB_HEADSET -> AudioRoute.USB
        AndroidAudioDeviceType.WIRED_HEADSET -> AudioRoute.WIRED_HEADSET
        AndroidAudioDeviceType.WIRED_HEADPHONES -> AudioRoute.WIRED_HEADPHONES
        AndroidAudioDeviceType.LINE_ANALOG, AndroidAudioDeviceType.AUX_LINE -> AudioRoute.ANALOG_LINE
        AndroidAudioDeviceType.BUILTIN_MIC -> AudioRoute.BUILTIN_MIC
        AndroidAudioDeviceType.BUILTIN_SPEAKER, AndroidAudioDeviceType.BUILTIN_SPEAKER_SAFE -> AudioRoute.BUILTIN_SPEAKER
        else -> null
    }

    private fun baseRouteKey(direction: String, kind: String, address: String, productName: String): String {
        val stableHint = normalizedAddress(address) ?: if (kind == AudioRoute.USB) {
            normalizedAddress(productName)?.replace(Regex("-\\d+$"), "")
        } else null
        return listOfNotNull("android", kind, direction, stableHint).joinToString(":")
    }

    private fun manifestId(direction: String, kind: String, id: Int, routeKey: String): String =
        if (kind == AudioRoute.USB) "android-$direction-$id" else stableManifestId(routeKey)

    private fun socketPath(direction: String, kind: String, id: Int, routeKey: String): String =
        if (kind == AudioRoute.USB) "/opt/tx5dr-data/runtime/sockets/audio-$direction-$id.sock" else stableSocketPath(routeKey)

    private fun stableManifestId(routeKey: String): String {
        val parts = routeKey.split(':')
        val kind = parts.getOrNull(1) ?: "unknown"
        val direction = parts.getOrNull(2) ?: "unknown"
        val suffix = parts.drop(3).takeIf { it.isNotEmpty() }?.joinToString("-")
        return listOfNotNull("android", direction, kind, suffix).joinToString("-")
    }

    private fun stableSocketPath(routeKey: String): String {
        val direction = when (routeKey.split(':').getOrNull(2)) {
            "input" -> "i"
            "output" -> "o"
            else -> "x"
        }
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(routeKey.toByteArray(Charsets.UTF_8))
            .take(8)
            .joinToString("") { byte -> String.format(Locale.US, "%02x", byte.toInt() and 0xff) }
        return "/opt/tx5dr-data/runtime/sockets/a$direction-$digest.sock"
    }

    private fun normalizedAddress(address: String?): String? {
        val normalized = address.orEmpty().trim().lowercase(Locale.US)
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
        return normalized.takeIf { it.isNotEmpty() }?.take(32)
    }

    private val inputTypes = setOf(
        AndroidAudioDeviceType.USB_DEVICE,
        AndroidAudioDeviceType.USB_HEADSET,
        AndroidAudioDeviceType.WIRED_HEADSET,
        AndroidAudioDeviceType.LINE_ANALOG,
        AndroidAudioDeviceType.AUX_LINE,
        AndroidAudioDeviceType.BUILTIN_MIC,
    )

    private val outputTypes = setOf(
        AndroidAudioDeviceType.USB_DEVICE,
        AndroidAudioDeviceType.USB_HEADSET,
        AndroidAudioDeviceType.WIRED_HEADSET,
        AndroidAudioDeviceType.WIRED_HEADPHONES,
        AndroidAudioDeviceType.LINE_ANALOG,
        AndroidAudioDeviceType.AUX_LINE,
        AndroidAudioDeviceType.BUILTIN_SPEAKER,
        AndroidAudioDeviceType.BUILTIN_SPEAKER_SAFE,
    )
}

data class AndroidAudioEndpoint(
    val id: Int,
    val manifestId: String,
    val routeKey: String,
    val direction: String,
    val kind: String,
    val transport: String,
    val connector: String,
    val type: Int,
    val name: String,
    val address: String?,
    val sampleRates: List<Int>,
    val channelCounts: List<Int>,
    val socketPath: String,
    val available: Boolean = true,
    val clientConnected: Boolean = false,
    val routeState: String = AudioRouteState.IDLE,
    val routeVerified: Boolean = false,
    val failureReason: String? = null,
    val audioSource: String? = null,
) {
    @Deprecated("Use clientConnected", ReplaceWith("clientConnected"))
    val connected: Boolean get() = clientConnected
}

data class AndroidAudioStatus(
    val state: String = "stopped",
    val inputDevices: List<AndroidAudioEndpoint> = emptyList(),
    val outputDevices: List<AndroidAudioEndpoint> = emptyList(),
    val recordPermissionGranted: Boolean = false,
    val error: String? = null,
)

@Deprecated("Use AndroidAudioEndpoint", ReplaceWith("AndroidAudioEndpoint"))
typealias UsbAudioDevice = AndroidAudioEndpoint

@Deprecated("Use AndroidAudioStatus", ReplaceWith("AndroidAudioStatus"))
typealias UsbAudioStatus = AndroidAudioStatus
