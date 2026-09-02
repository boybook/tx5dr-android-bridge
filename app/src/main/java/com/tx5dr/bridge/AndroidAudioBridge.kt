package com.tx5dr.bridge

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioRouting
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.roundToInt


internal const val ANDROID_AUDIO_OUTPUT_HEADER_BYTES = 16
private val ANDROID_AUDIO_OUTPUT_HEADER_MAGIC_V1 = byteArrayOf(
    'T'.code.toByte(), 'X'.code.toByte(), '5'.code.toByte(), 'D'.code.toByte(),
    'R'.code.toByte(), 'A'.code.toByte(), 'O'.code.toByte(), '1'.code.toByte(),
)
private val ANDROID_AUDIO_OUTPUT_HEADER_MAGIC_V2 = byteArrayOf(
    'T'.code.toByte(), 'X'.code.toByte(), '5'.code.toByte(), 'D'.code.toByte(),
    'R'.code.toByte(), 'A'.code.toByte(), 'O'.code.toByte(), '2'.code.toByte(),
)
private val ANDROID_AUDIO_OUTPUT_ROUTE_ACK = "TX5DRAK1".toByteArray(Charsets.US_ASCII)

internal enum class AndroidAudioOutputPcmFormat(
    val id: Int,
    val label: String,
    val encoding: Int,
    val bytesPerSample: Int,
) {
    S16LE(1, "s16le", AudioFormat.ENCODING_PCM_16BIT, 2),
    F32LE(2, "f32le", AudioFormat.ENCODING_PCM_FLOAT, 4),
}

internal data class AndroidAudioOutputStreamHeader(
    val sampleRate: Int,
    val format: AndroidAudioOutputPcmFormat,
    val channels: Int,
    val routeAckRequested: Boolean = false,
)

internal sealed class AndroidAudioOutputHeaderParseResult {
    data class Header(val header: AndroidAudioOutputStreamHeader) : AndroidAudioOutputHeaderParseResult()
    data object Legacy : AndroidAudioOutputHeaderParseResult()
    data class Invalid(val reason: String) : AndroidAudioOutputHeaderParseResult()
}

internal fun parseAndroidAudioOutputHeader(bytes: ByteArray): AndroidAudioOutputHeaderParseResult {
    if (bytes.size < ANDROID_AUDIO_OUTPUT_HEADER_BYTES) {
        return AndroidAudioOutputHeaderParseResult.Invalid("short header: ${bytes.size}/$ANDROID_AUDIO_OUTPUT_HEADER_BYTES bytes")
    }
    val isV1 = ANDROID_AUDIO_OUTPUT_HEADER_MAGIC_V1.indices.all { bytes[it] == ANDROID_AUDIO_OUTPUT_HEADER_MAGIC_V1[it] }
    val isV2 = ANDROID_AUDIO_OUTPUT_HEADER_MAGIC_V2.indices.all { bytes[it] == ANDROID_AUDIO_OUTPUT_HEADER_MAGIC_V2[it] }
    if (!isV1 && !isV2) {
        val framedPrefix = "TX5DRAO".toByteArray(Charsets.US_ASCII)
        val looksFramed = framedPrefix.indices.all { bytes[it] == framedPrefix[it] }
        return if (looksFramed) {
            AndroidAudioOutputHeaderParseResult.Invalid("unsupported protocol version=${bytes[7].toInt().toChar()}")
        } else {
            AndroidAudioOutputHeaderParseResult.Legacy
        }
    }
    val sampleRate = readUInt32Le(bytes, 8)
    if (sampleRate !in 8_000..192_000) {
        return AndroidAudioOutputHeaderParseResult.Invalid("unsupported sampleRate=$sampleRate")
    }
    val formatId = bytes[12].toInt() and 0xff
    val format = AndroidAudioOutputPcmFormat.entries.firstOrNull { it.id == formatId }
        ?: return AndroidAudioOutputHeaderParseResult.Invalid("unsupported formatId=$formatId")
    val channels = bytes[13].toInt() and 0xff
    if (channels != 1) {
        return AndroidAudioOutputHeaderParseResult.Invalid("unsupported channels=$channels")
    }
    return AndroidAudioOutputHeaderParseResult.Header(AndroidAudioOutputStreamHeader(sampleRate, format, channels, routeAckRequested = isV2))
}

private fun readUInt32Le(bytes: ByteArray, offset: Int): Int {
    val value = (bytes[offset].toLong() and 0xffL) or
        ((bytes[offset + 1].toLong() and 0xffL) shl 8) or
        ((bytes[offset + 2].toLong() and 0xffL) shl 16) or
        ((bytes[offset + 3].toLong() and 0xffL) shl 24)
    return value.toInt()
}

object AndroidAudioBridge {
    private const val TAG = "AudioBridge"
    private const val STATS_INTERVAL_MS = 5000L
    private const val SLOW_INPUT_WRITE_MS = 200.0
    private const val SILENCE_PEAK_THRESHOLD = 4
    private const val ROUTE_LOSS_DEBOUNCE_MS = 250L
    private val sampleRates = intArrayOf(48000, 44100)
    private val listeners = CopyOnWriteArrayList<(AndroidAudioStatus) -> Unit>()

    @Volatile private var status = AndroidAudioStatus()
    @Volatile private var running = false
    private val sessions = mutableMapOf<String, AudioSocketSession>()
    private val nextSessionGeneration = AtomicLong(0)
    @Volatile private var coordinatorThread: Thread? = null
    private val coordinator = Executors.newSingleThreadExecutor { task ->
        Thread(task, "tx5dr-audio-coordinator").also { coordinatorThread = it }
    }
    private var devicesFile: File? = null
    @Volatile private var deviceCallbackRegistered = false
    @Volatile private var noisyReceiverRegistered = false
    @Volatile private var microphoneCaptureEnabled = false

    fun init(context: Context, targetFile: File) {
        devicesFile = targetFile
        refreshDevices(context)
    }

    fun addListener(listener: (AndroidAudioStatus) -> Unit) {
        listeners.add(listener)
        runCatching { listener(status) }.onFailure { Log.w(TAG, "Android audio listener failed", it) }
    }

    fun removeListener(listener: (AndroidAudioStatus) -> Unit) {
        listeners.remove(listener)
    }

    fun hasRecordPermission(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    fun refreshDevices(context: Context): AndroidAudioStatus {
        val manager = context.applicationContext.getSystemService(AudioManager::class.java)
        val detectedInputs = uniqueBridgeDevices(manager?.getDevices(AudioManager.GET_DEVICES_INPUTS).orEmpty()
            .mapNotNull { it.toBridgeDevice("input") })
        val detectedOutputs = uniqueBridgeDevices(manager?.getDevices(AudioManager.GET_DEVICES_OUTPUTS).orEmpty()
            .mapNotNull { it.toBridgeDevice("output") })
        return coordinated {
            val inputs = AndroidAudioDevicePolicy.mergeEndpointState("input", detectedInputs, status.inputDevices)
            val outputs = AndroidAudioDevicePolicy.mergeEndpointState("output", detectedOutputs, status.outputDevices)
            val permissionGranted = hasRecordPermission(context)
            val nextState = when {
                running && !permissionGranted -> "output-only"
                !permissionGranted && status.state == "permission-denied" -> "permission-denied"
                !permissionGranted -> "permission-required"
                else -> status.state
            }
            updateNow(
                status.copy(
                    state = nextState,
                    inputDevices = inputs,
                    outputDevices = outputs,
                    recordPermissionGranted = permissionGranted,
                    error = null,
                )
            )
            status
        }
    }

    fun startWatchingDevices(context: Context) {
        if (deviceCallbackRegistered) return
        val app = context.applicationContext
        val manager = app.getSystemService(AudioManager::class.java) ?: return
        try {
            manager.registerAudioDeviceCallback(object : AudioDeviceCallback() {
                override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
                    handleDeviceChange(app, "added", addedDevices)
                }

                override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
                    handleDeviceChange(app, "removed", removedDevices)
                }
            }, Handler(Looper.getMainLooper()))
            deviceCallbackRegistered = true
        } catch (error: Throwable) {
            LogBus.w(TAG, "Unable to register audio device callback: ${error.message}")
        }
        registerNoisyReceiver(app)
    }

    fun startIfPermitted(context: Context): Boolean {
        refreshDevices(context)
        val inputs = if (hasRecordPermission(context)) currentInputDevices(context) else emptyList()
        val outputs = currentOutputDevices(context)
        if (inputs.isEmpty() && outputs.isEmpty()) {
            update { it.copy(state = "no-device", error = "No supported Android audio device is available") }
            return false
        }
        start(context)
        return true
    }

    fun start(context: Context) {
        val app = context.applicationContext
        refreshDevices(app)
        val permissionGranted = hasRecordPermission(app)
        val inputs = if (permissionGranted) currentInputDevices(app) else emptyList()
        val outputs = currentOutputDevices(app)
        if (inputs.isEmpty() && outputs.isEmpty()) {
            update { it.copy(state = "no-device", error = "No supported Android audio device is available") }
            return
        }
        microphoneCaptureEnabled = permissionGranted && inputs.isNotEmpty()
        if (microphoneCaptureEnabled) {
            BridgeService.start(app, BridgeService.ACTION_ENABLE_MICROPHONE_FOREGROUND)
        } else {
            BridgeService.start(app, BridgeService.ACTION_DISABLE_MICROPHONE_FOREGROUND)
        }
        if (running) {
            coordinated { startAudioSessions(app, inputs, outputs) }
            update { it.copy(state = if (permissionGranted) "streaming" else "output-only", error = null) }
            return
        }
        running = true
        update { it.copy(state = "starting", error = null) }
        coordinated { startAudioSessions(app, inputs, outputs) }
        update { it.copy(state = if (permissionGranted) "streaming" else "output-only") }
    }

    fun markPermissionDenied() {
        update { it.copy(state = "permission-denied", error = null) }
    }

    fun stop() {
        stop(stopLinuxSide = true)
    }

    fun restartIfRunning(context: Context) {
        val app = context.applicationContext
        if (!running) {
            refreshDevices(app)
            return
        }
        LogBus.i(TAG, "Restarting Android audio sessions with bufferTargetMs=${bufferTargetMs()}")
        stop(stopLinuxSide = false)
        start(app)
    }

    private fun stop(stopLinuxSide: Boolean) {
        running = false
        microphoneCaptureEnabled = false
        val activeSessions = coordinated {
            sessions.values.toList().also { sessions.clear() }
        }
        activeSessions.forEach { it.stop() }
        BridgeService.start(BridgeRuntime.appContext(), BridgeService.ACTION_DISABLE_MICROPHONE_FOREGROUND)
        if (stopLinuxSide) BridgeRuntime.stopLinuxAudioSide()
        update { current ->
            current.copy(
                state = "stopped",
                inputDevices = current.inputDevices.map(AndroidAudioDevicePolicy::resetReleasedEndpoint),
                outputDevices = current.outputDevices.map(AndroidAudioDevicePolicy::resetReleasedEndpoint),
            )
        }
    }

    private fun handleDeviceChange(context: Context, reason: String, devices: Array<out AudioDeviceInfo>) {
        val audioChanged = devices.any { AndroidAudioDevicePolicy.classify(it.toDescriptor("input")) != null || AndroidAudioDevicePolicy.classify(it.toDescriptor("output")) != null }
        if (!audioChanged) return
        LogBus.i(TAG, "Audio device $reason")
        refreshDevices(context)
        if (running) {
            coordinator.execute {
                runCatching {
                    val inputs = if (microphoneCaptureEnabled && hasRecordPermission(context)) currentInputDevices(context) else emptyList()
                    startAudioSessions(context.applicationContext, inputs, currentOutputDevices(context))
                    refreshDevices(context)
                    update { it.copy(state = if (hasRecordPermission(context)) "streaming" else "output-only") }
                }.onFailure { error ->
                    LogBus.e(TAG, "Audio hotplug reconcile failed", error)
                    update { it.copy(state = "error", error = error.message) }
                }
            }
        }
    }

    private fun startAudioSessions(context: Context, inputs: List<AudioDeviceInfo>, outputs: List<AudioDeviceInfo>) {
        val inputEndpoints = status.inputDevices.associateBy { it.id }
        val outputEndpoints = status.outputDevices.associateBy { it.id }
        val desiredKeys = (inputs.mapNotNull { inputEndpoints[it.id]?.routeKey } + outputs.mapNotNull { outputEndpoints[it.id]?.routeKey }).toSet()
        sessions.filterKeys { it !in desiredKeys }.toMap().forEach { (key, session) ->
            sessions.remove(key)
            session.stop()
            resetReleasedEndpointNow(key)
        }
        inputs.forEach { device ->
            val bridgeDevice = inputEndpoints[device.id] ?: device.toBridgeDevice("input") ?: return@forEach
            val key = bridgeDevice.routeKey
            val existing = sessions[key]
            if (existing?.isAlive == true && existing.device.id == bridgeDevice.id) return@forEach
            sessions.remove(key)
            existing?.stop()
            if (existing != null) resetReleasedEndpointNow(key)
            AudioSocketSession(bridgeDevice, nextSessionGeneration.incrementAndGet()).also { session ->
                sessions[key] = session
                session.thread = Thread { inputLoop(context, device, bridgeDevice, session) }.also { thread ->
                    thread.name = "tx5dr-audio-input-${device.id}"
                    thread.start()
                }
            }
        }
        outputs.forEach { device ->
            val bridgeDevice = outputEndpoints[device.id] ?: device.toBridgeDevice("output") ?: return@forEach
            val key = bridgeDevice.routeKey
            val existing = sessions[key]
            if (existing?.isAlive == true && existing.device.id == bridgeDevice.id) return@forEach
            sessions.remove(key)
            existing?.stop()
            if (existing != null) resetReleasedEndpointNow(key)
            AudioSocketSession(bridgeDevice, nextSessionGeneration.incrementAndGet()).also { session ->
                sessions[key] = session
                session.thread = Thread { outputLoop(context, device, bridgeDevice, session) }.also { thread ->
                    thread.name = "tx5dr-audio-output-${device.id}"
                    thread.start()
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun inputLoop(context: Context, device: AudioDeviceInfo, bridgeDevice: AndroidAudioEndpoint, session: AudioSocketSession) {
        setAudioThreadPriority("input-${device.id}")
        try {
            val formatAndBuffer = chooseRecordFormat(device)
            val format = AudioFormat.Builder()
                .setSampleRate(formatAndBuffer.sampleRate)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                .build()
            AndroidUnixSocketServer(androidSocketFile(bridgeDevice.socketPath)).use { server ->
                session.server = server
                LogBus.i(TAG, "Audio input available on ${bridgeDevice.socketPath} device=${device.describe()} rate=${formatAndBuffer.sampleRate} buffer=${formatAndBuffer.bufferSize} target=${formatAndBuffer.bufferTargetMs}ms")
                while (running && session.running) {
                    try {
                        server.accept().use { socket ->
                            session.setFailAction { runCatching { socket.close() } }
                            session.clearFailure()
                            markEndpointState(bridgeDevice.routeKey, AudioRouteState.OPENING, clientConnected = true, session = session)
                            val recordBuild = try {
                                buildRecorder(format, formatAndBuffer.bufferSize, device)
                            } catch (error: Throwable) {
                                throw AudioRouteException(AudioRouteState.FAILED, "Unable to open Android input: ${error.message}")
                            }
                            val recorder = recordBuild.recorder
                            val jitterBuffer = PcmJitterBuffer(
                                sampleRate = formatAndBuffer.sampleRate,
                                targetMs = formatAndBuffer.bufferTargetMs,
                                nominalChunkBytes = PcmJitterBuffer.logicalChunkBytes(formatAndBuffer.sampleRate, formatAndBuffer.bufferTargetMs),
                                mode = PcmJitterMode.INPUT,
                            )
                            val connected = AtomicBoolean(true)
                            val routeVerifiedSignal = AtomicBoolean(false)
                            val routeHandler = Handler(Looper.getMainLooper())
                            val captureError = AtomicReference<Throwable?>(null)
                            val stats = InputStats(formatAndBuffer.sampleRate, formatAndBuffer.bufferSize, jitterBuffer)
                            fun failInputRoute(reason: String) {
                                session.failClosed(AudioRouteState.LOST, reason)
                                markEndpointFailure(bridgeDevice.routeKey, AudioRouteState.LOST, reason, session)
                            }
                            val routeListener = AudioRouting.OnRoutingChangedListener { routing ->
                                val routed = routing.routedDevice
                                if (connected.get() && routeVerifiedSignal.get()) {
                                    if (routed != null && !sameAudioDevice(routed, device)) {
                                        failInputRoute("Input route changed to ${routed.describe()}")
                                    } else if (routed == null) {
                                        routeHandler.postDelayed({
                                            if (connected.get() && routeVerifiedSignal.get() && recorder.routedDevice == null) {
                                                failInputRoute("Android removed the verified input route")
                                            }
                                        }, ROUTE_LOSS_DEBOUNCE_MS)
                                    }
                                }
                            }
                            recorder.addOnRoutingChangedListener(routeListener, Handler(Looper.getMainLooper()))
                            val recordingCallback = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                object : AudioManager.AudioRecordingCallback() {
                                    override fun onRecordingConfigChanged(configs: MutableList<android.media.AudioRecordingConfiguration>) {
                                        val config = configs.firstOrNull { it.clientAudioSessionId == recorder.audioSessionId }
                                        if (config == null) {
                                            if (connected.get() && routeVerifiedSignal.get()) {
                                                routeHandler.postDelayed({
                                                    if (connected.get() && routeVerifiedSignal.get() && recorder.activeRecordingConfiguration == null) {
                                                        failInputRoute("Android removed the active input recording configuration")
                                                    }
                                                }, ROUTE_LOSS_DEBOUNCE_MS)
                                            }
                                            return
                                        }
                                        val routed = config.audioDevice
                                        val failure = when {
                                            config.isClientSilenced -> "Android silenced the radio audio input client"
                                            routed != null && !sameAudioDevice(routed, device) -> "Input recording moved to ${routed.describe()}"
                                            else -> null
                                        }
                                        if (failure != null && connected.get()) {
                                            failInputRoute(failure)
                                        }
                                    }
                                }
                            } else null
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && recordingCallback != null) {
                                runCatching { recorder.registerAudioRecordingCallback(context.mainExecutor, recordingCallback) }
                                    .onFailure { LogBus.w(TAG, "Unable to monitor Android input silencing: ${it.message}") }
                            }
                            session.setFailAction {
                                connected.set(false)
                                jitterBuffer.close()
                                runCatching { recorder.stop() }
                                runCatching { socket.close() }
                            }
                            try {
                                val routed = awaitRoutedDevice(recorder)
                                session.failure()?.let { failure ->
                                    throw AudioRouteException(failure.routeState, failure.reason)
                                }
                                if (routed == null || !sameAudioDevice(routed, device)) {
                                    throw AudioRouteException(
                                        AudioRouteState.LOST,
                                        "Input route verification failed: expected ${device.describe()}, actual ${routed?.describe() ?: "none"}",
                                    )
                                }
                                routeVerifiedSignal.set(true)
                            } catch (error: Throwable) {
                                session.failClosed(
                                    (error as? AudioRouteException)?.routeState ?: AudioRouteState.FAILED,
                                    error.message ?: "Unable to start input route",
                                )
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && recordingCallback != null) {
                                    runCatching { recorder.unregisterAudioRecordingCallback(recordingCallback) }
                                }
                                recorder.removeOnRoutingChangedListener(routeListener)
                                recorder.release()
                                throw error
                            }
                            markEndpointState(
                                bridgeDevice.routeKey,
                                AudioRouteState.VERIFIED,
                                clientConnected = true,
                                routeVerified = true,
                                audioSource = sourceName(recordBuild.audioSource),
                                session = session,
                            )
                            LogBus.i(TAG, "TX-5DR Android input backend connected: ${bridgeDevice.name} source=${sourceName(recordBuild.audioSource)} routed=${routedDeviceName(recorder)}")
                            val captureThread = Thread {
                                setAudioThreadPriority("input-capture-${device.id}")
                                val buffer = ByteArray(formatAndBuffer.bufferSize)
                                var sequence = 0L
                                try {
                                    while (running && session.running && connected.get() && !Thread.currentThread().isInterrupted) {
                                        val beforeRead = System.nanoTime()
                                        val n = recorder.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
                                        val readMs = elapsedMsSince(beforeRead)
                                        if (n > 0) {
                                            stats.recordCaptured(buffer, n, readMs, sequence)
                                            jitterBuffer.offer(PcmChunk(buffer.copyOf(n), n, System.currentTimeMillis(), sequence++, peakAbsPcm16(buffer, n)))
                                        } else if (n == 0) {
                                            stats.recordReadError()
                                            Thread.sleep(2)
                                        } else {
                                            stats.recordReadError()
                                            throw AudioRouteException(
                                                AudioRouteState.FAILED,
                                                "AudioRecord read failed with code $n",
                                            )
                                        }
                                        stats.logIfDue("Android audio input stats")
                                    }
                                } catch (error: Throwable) {
                                    captureError.set(error)
                                } finally {
                                    jitterBuffer.close()
                                }
                            }.also { it.name = "tx5dr-android-audio-input-capture-${device.id}"; it.start() }

                            update { it.copy(state = "streaming") }
                            val out = socket.getOutputStream()
                            try {
                                while (running && session.running && connected.get()) {
                                    val take = jitterBuffer.take(500)
                                    if (take.state == PcmTakeState.CLOSED) break
                                    if (take.state != PcmTakeState.CHUNK) {
                                        stats.logIfDue("Android audio input stats")
                                        continue
                                    }
                                    val chunk = take.chunk ?: continue
                                    val ageMs = System.currentTimeMillis() - chunk.createdAtMs
                                    val beforeWrite = System.nanoTime()
                                    out.write(chunk.bytes, 0, chunk.length)
                                    out.flush()
                                    val writeMs = elapsedMsSince(beforeWrite)
                                    stats.recordWritten(chunk.length, ageMs, writeMs)
                                    stats.logIfDue("Android audio input stats")
                                }
                            } finally {
                                connected.set(false)
                                routeVerifiedSignal.set(false)
                                jitterBuffer.close()
                                session.clearFailAction()
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && recordingCallback != null) {
                                    runCatching { recorder.unregisterAudioRecordingCallback(recordingCallback) }
                                }
                                recorder.removeOnRoutingChangedListener(routeListener)
                                try { recorder.stop() } catch (_: Throwable) {}
                                recorder.release()
                                try { socket.close() } catch (_: Throwable) {}
                                captureThread.join(1000)
                                markEndpointDisconnected(bridgeDevice.routeKey, session)
                                captureError.get()?.let { throw it }
                                LogBus.i(TAG, "TX-5DR Android input backend disconnected: ${bridgeDevice.name}")
                            }
                        }
                    } catch (error: Throwable) {
                        if (running && session.running) {
                            if (error is AudioRouteException) session.recordFailure(error.routeState, error.message ?: "Input route failed")
                            markEndpointDisconnected(bridgeDevice.routeKey, session)
                            LogBus.w(TAG, "Android audio input client ended for ${bridgeDevice.name}: ${error.message}")
                        }
                    }
                }
            }
        } catch (error: Throwable) {
            if (running) {
                LogBus.e(TAG, "Android audio input bridge failed", error)
                update { it.copy(state = "error", error = error.message) }
            }
        } finally {
            markEndpointDisconnected(bridgeDevice.routeKey, session)
            session.server = null
        }
    }

    private fun outputLoop(context: Context, device: AudioDeviceInfo, bridgeDevice: AndroidAudioEndpoint, session: AudioSocketSession) {
        setAudioThreadPriority("output-${device.id}")
        try {
            val targetMs = bufferTargetMs()
            val attrs = outputAudioAttributes()
            AndroidUnixSocketServer(androidSocketFile(bridgeDevice.socketPath)).use { server ->
                session.server = server
                LogBus.i(TAG, "Audio output available on ${bridgeDevice.socketPath} device=${device.describe()} legacyRate=${choosePlaybackRate(device)} target=${targetMs}ms")
                while (running && session.running) {
                    try {
                        server.accept().use { socket ->
                            session.setFailAction { runCatching { socket.close() } }
                            session.clearFailure()
                            val input = socket.getInputStream()
                            val prefix = readExactly(input, ANDROID_AUDIO_OUTPUT_HEADER_BYTES)
                            if (prefix == null) {
                                LogBus.w(TAG, "Android audio output client closed before stream header for ${bridgeDevice.name}")
                                return@use
                            }
                            val legacyHeader = AndroidAudioOutputStreamHeader(
                                sampleRate = choosePlaybackRate(device),
                                format = AndroidAudioOutputPcmFormat.S16LE,
                                channels = 1,
                            )
                            val headerResult = parseAndroidAudioOutputHeader(prefix)
                            val streamHeader = when (headerResult) {
                                is AndroidAudioOutputHeaderParseResult.Header -> headerResult.header
                                AndroidAudioOutputHeaderParseResult.Legacy -> legacyHeader
                                is AndroidAudioOutputHeaderParseResult.Invalid -> {
                                    LogBus.e(TAG, "Invalid Android audio output stream header for ${bridgeDevice.name}: ${headerResult.reason}")
                                    return@use
                                }
                            }
                            val legacyPrefix = if (headerResult is AndroidAudioOutputHeaderParseResult.Legacy) prefix else null
                            val minBuffer = AudioTrack.getMinBufferSize(
                                streamHeader.sampleRate,
                                AudioFormat.CHANNEL_OUT_MONO,
                                streamHeader.format.encoding,
                            )
                            if (minBuffer <= 0) {
                                throw AudioRouteException(
                                    AudioRouteState.FAILED,
                                    "Unsupported Android output contract rate=${streamHeader.sampleRate} format=${streamHeader.format.label} minBuffer=$minBuffer",
                                )
                            }
                            val bufferSize = bufferSizeFor(streamHeader.sampleRate, minBuffer, targetMs, streamHeader.format.bytesPerSample)
                            val format = AudioFormat.Builder()
                                .setSampleRate(streamHeader.sampleRate)
                                .setEncoding(streamHeader.format.encoding)
                                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                                .build()
                            val trackBuilder = AudioTrack.Builder()
                                .setAudioAttributes(attrs)
                                .setAudioFormat(format)
                                .setBufferSizeInBytes(bufferSize)
                                .setTransferMode(AudioTrack.MODE_STREAM)
                            trackBuilder.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                            val track = try {
                                trackBuilder.build()
                            } catch (error: Throwable) {
                                throw AudioRouteException(AudioRouteState.FAILED, "Unable to open Android output: ${error.message}")
                            }
                            if (track.state != AudioTrack.STATE_INITIALIZED) {
                                track.release()
                                throw AudioRouteException(AudioRouteState.FAILED, "AudioTrack failed to initialize for ${bridgeDevice.name}")
                            }
                            val preferredDeviceApplied = track.setPreferredDevice(device)
                            if (!preferredDeviceApplied) {
                                track.release()
                                throw AudioRouteException(AudioRouteState.FAILED, "Android rejected the requested output route")
                            }
                            markEndpointState(bridgeDevice.routeKey, AudioRouteState.OPENING, clientConnected = true, session = session)
                            LogBus.i(
                                TAG,
                                "TX-5DR Android output backend connected: ${bridgeDevice.name} kind=${bridgeDevice.kind} device=${device.describe()} rate=${streamHeader.sampleRate} encoding=${streamHeader.format.label}/${streamHeader.format.encoding} channelMask=MONO buffer=$bufferSize minBuffer=$minBuffer usage=${attrs.usage} contentType=${attrs.contentType} volumeStream=${attrs.volumeControlStream} preferredDeviceApplied=$preferredDeviceApplied legacy=${legacyPrefix != null}",
                            )
                            val jitterBuffer = PcmJitterBuffer(
                                sampleRate = streamHeader.sampleRate,
                                targetMs = targetMs,
                                nominalChunkBytes = PcmJitterBuffer.logicalChunkBytes(streamHeader.sampleRate, targetMs, streamHeader.format.bytesPerSample),
                                mode = PcmJitterMode.OUTPUT,
                                bytesPerSample = streamHeader.format.bytesPerSample,
                            )
                            val connected = AtomicBoolean(true)
                            val routeVerifiedSignal = AtomicBoolean(false)
                            val routeHandler = Handler(Looper.getMainLooper())
                            val readerError = AtomicReference<Throwable?>(null)
                            val stats = OutputStats(streamHeader.sampleRate, streamHeader.format.label, streamHeader.format.bytesPerSample, bufferSize, jitterBuffer)
                            val manager = context.getSystemService(AudioManager::class.java)
                            val focusRequest = AtomicReference<AudioFocusRequest?>(null)
                            val requiresExclusiveFocus = bridgeDevice.kind in setOf(
                                AudioRoute.WIRED_HEADSET,
                                AudioRoute.WIRED_HEADPHONES,
                                AudioRoute.ANALOG_LINE,
                            )
                            fun abandonAudioFocus() {
                                focusRequest.getAndSet(null)?.let { request ->
                                    runCatching { manager?.abandonAudioFocusRequest(request) }
                                }
                            }
                            fun requestAudioFocusOrFail() {
                                if (!requiresExclusiveFocus || focusRequest.get() != null) return
                                val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                                    .setAudioAttributes(attrs)
                                    .setAcceptsDelayedFocusGain(false)
                                    .setOnAudioFocusChangeListener({ change ->
                                        if (change != AudioManager.AUDIOFOCUS_GAIN && connected.get()) {
                                            val reason = "Exclusive audio focus was lost while using the analog output"
                                            session.failClosed(AudioRouteState.LOST, reason)
                                            markEndpointFailure(bridgeDevice.routeKey, AudioRouteState.LOST, reason, session)
                                        }
                                    }, Handler(Looper.getMainLooper()))
                                    .build()
                                if (manager?.requestAudioFocus(request) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                                    val reason = "Exclusive audio focus is unavailable for the analog output"
                                    session.recordFailure(AudioRouteState.FAILED, reason)
                                    throw AudioRouteException(AudioRouteState.FAILED, reason)
                                }
                                focusRequest.set(request)
                            }
                            val routeListener = AudioRouting.OnRoutingChangedListener { routing ->
                                val routed = routing.routedDevice
                                if (connected.get() && routeVerifiedSignal.get()) {
                                    if (routed != null && !sameAudioDevice(routed, device)) {
                                        val reason = "Output route changed to ${routed.describe()}"
                                        session.failClosed(AudioRouteState.LOST, reason)
                                        markEndpointFailure(bridgeDevice.routeKey, AudioRouteState.LOST, reason, session)
                                    } else if (routed == null) {
                                        routeHandler.postDelayed({
                                            if (connected.get() && routeVerifiedSignal.get() && track.routedDevice == null) {
                                                val reason = "Android removed the verified output route"
                                                session.failClosed(AudioRouteState.LOST, reason)
                                                markEndpointFailure(bridgeDevice.routeKey, AudioRouteState.LOST, reason, session)
                                            }
                                        }, ROUTE_LOSS_DEBOUNCE_MS)
                                    }
                                }
                            }
                            track.addOnRoutingChangedListener(routeListener, Handler(Looper.getMainLooper()))
                            session.setFailAction {
                                connected.set(false)
                                jitterBuffer.close()
                                abandonAudioFocus()
                                pauseFlush(track)
                                runCatching { socket.close() }
                            }
                            var nextSequence = 0L
                            if (legacyPrefix != null) {
                                stats.recordSocketRead(legacyPrefix.size, 0.0, nextSequence)
                                jitterBuffer.offer(
                                    PcmChunk(
                                        legacyPrefix,
                                        legacyPrefix.size,
                                        System.currentTimeMillis(),
                                        nextSequence++,
                                        peakAbsPcm(legacyPrefix, legacyPrefix.size, streamHeader.format),
                                    )
                                )
                            }
                            val readerThread = Thread {
                                setAudioThreadPriority("output-reader-${device.id}")
                                val buffer = ByteArray(bufferSize)
                                var sequence = nextSequence
                                var tail = ByteArray(0)
                                try {
                                    while (running && session.running && connected.get() && !Thread.currentThread().isInterrupted) {
                                        if (!jitterBuffer.waitForProducerRoom(50)) {
                                            stats.logIfDue("Android audio output stats")
                                            continue
                                        }
                                        val beforeRead = System.nanoTime()
                                        val n = input.read(buffer)
                                        val readMs = elapsedMsSince(beforeRead)
                                        if (n <= 0) break
                                        stats.recordSocketRead(n, readMs, sequence)
                                        val raw = if (tail.isEmpty()) buffer.copyOf(n) else tail + buffer.copyOf(n)
                                        val alignedLength = raw.size - (raw.size % streamHeader.format.bytesPerSample)
                                        tail = if (alignedLength < raw.size) raw.copyOfRange(alignedLength, raw.size) else ByteArray(0)
                                        if (alignedLength <= 0) {
                                            stats.logIfDue("Android audio output stats")
                                            continue
                                        }
                                        val pcm = raw.copyOf(alignedLength)
                                        jitterBuffer.offer(PcmChunk(pcm, pcm.size, System.currentTimeMillis(), sequence++, peakAbsPcm(pcm, pcm.size, streamHeader.format)))
                                        stats.logIfDue("Android audio output stats")
                                    }
                                } catch (error: Throwable) {
                                    if (running && connected.get()) readerError.set(error)
                                } finally {
                                    jitterBuffer.close()
                                }
                            }.also { it.name = "tx5dr-android-audio-output-reader-${device.id}"; it.start() }

                            var active = false
                            fun activateAndVerifyRoute() {
                                requestAudioFocusOrFail()
                                pauseFlush(track)
                                track.play()
                                if (!primeOutputRoute(track, streamHeader)) {
                                    val reason = "Unable to prime the requested output route"
                                    session.failClosed(AudioRouteState.FAILED, reason)
                                    throw AudioRouteException(AudioRouteState.FAILED, reason)
                                }
                                val routed = awaitRoutedDevice(track)
                                session.failure()?.let { failure ->
                                    throw AudioRouteException(failure.routeState, failure.reason)
                                }
                                if (routed == null || !sameAudioDevice(routed, device)) {
                                    val reason = "Output route verification failed: expected ${device.describe()}, actual ${routed?.describe() ?: "none"}"
                                    session.failClosed(AudioRouteState.LOST, reason)
                                    throw AudioRouteException(AudioRouteState.LOST, reason)
                                }
                                routeVerifiedSignal.set(true)
                                markEndpointState(
                                    bridgeDevice.routeKey,
                                    AudioRouteState.VERIFIED,
                                    clientConnected = true,
                                    routeVerified = true,
                                    session = session,
                                )
                                active = true
                                stats.recordFlush()
                            }
                            try {
                                if (streamHeader.routeAckRequested) {
                                    activateAndVerifyRoute()
                                    try {
                                        socket.getOutputStream().apply {
                                            write(ANDROID_AUDIO_OUTPUT_ROUTE_ACK)
                                            flush()
                                        }
                                    } catch (error: Throwable) {
                                        val reason = "Unable to acknowledge the verified output route: ${error.message}"
                                        session.failClosed(AudioRouteState.FAILED, reason)
                                        throw AudioRouteException(AudioRouteState.FAILED, reason)
                                    }
                                    LogBus.i(TAG, "Output route-ready ACK sent for ${bridgeDevice.routeKey}")
                                }
                                while (running && session.running && connected.get()) {
                                    val take = jitterBuffer.take(500)
                                    when (take.state) {
                                        PcmTakeState.CLOSED -> break
                                        PcmTakeState.WAITING -> {
                                            stats.logIfDue("Android audio output stats")
                                            continue
                                        }
                                        PcmTakeState.UNDERRUN -> {
                                            if (active) {
                                                pauseFlush(track)
                                                routeVerifiedSignal.set(false)
                                                abandonAudioFocus()
                                                markEndpointState(
                                                    bridgeDevice.routeKey,
                                                    AudioRouteState.OPENING,
                                                    clientConnected = true,
                                                    session = session,
                                                )
                                                active = false
                                                stats.recordFlush()
                                            }
                                            stats.logIfDue("Android audio output stats")
                                            continue
                                        }
                                        PcmTakeState.CHUNK -> Unit
                                    }
                                    val chunk = take.chunk ?: continue
                                    val ageMs = System.currentTimeMillis() - chunk.createdAtMs
                                    stats.recordChunkAge(ageMs)
                                    if (chunk.peak <= SILENCE_PEAK_THRESHOLD) {
                                        stats.recordSilence(chunk.length)
                                    }

                                    if (!active) {
                                        activateAndVerifyRoute()
                                    }

                                    if (!writePcmFully(track, chunk, streamHeader.format, stats, connected, session, ageMs)) break
                                    stats.logIfDue("Android audio output stats")
                                }
                            } finally {
                                connected.set(false)
                                routeVerifiedSignal.set(false)
                                jitterBuffer.close()
                                abandonAudioFocus()
                                session.clearFailAction()
                                track.removeOnRoutingChangedListener(routeListener)
                                pauseFlush(track)
                                track.release()
                                try { socket.close() } catch (_: Throwable) {}
                                readerThread.join(1000)
                                markEndpointDisconnected(bridgeDevice.routeKey, session)
                                readerError.get()?.let { throw it }
                                LogBus.i(TAG, "TX-5DR Android output backend disconnected: ${bridgeDevice.name}")
                            }
                        }
                    } catch (error: Throwable) {
                        if (running && session.running) {
                            if (error is AudioRouteException) session.recordFailure(error.routeState, error.message ?: "Output route failed")
                            markEndpointDisconnected(bridgeDevice.routeKey, session)
                            LogBus.w(TAG, "Android audio output client ended for ${bridgeDevice.name}: ${error.message}")
                        }
                    }
                }
            }
        } catch (error: Throwable) {
            if (running) {
                LogBus.e(TAG, "Android audio output bridge failed", error)
                update { it.copy(state = "error", error = error.message) }
            }
        } finally {
            markEndpointDisconnected(bridgeDevice.routeKey, session)
            session.server = null
        }
    }

    private fun pauseFlush(track: AudioTrack) {
        try { track.pause() } catch (_: Throwable) {}
        try { track.flush() } catch (_: Throwable) {}
    }

    private fun primeOutputRoute(track: AudioTrack, header: AndroidAudioOutputStreamHeader): Boolean {
        val sampleCount = (header.sampleRate / 100).coerceAtLeast(1)
        return when (header.format) {
            AndroidAudioOutputPcmFormat.S16LE ->
                track.write(ByteArray(sampleCount * 2), 0, sampleCount * 2, AudioTrack.WRITE_BLOCKING) > 0
            AndroidAudioOutputPcmFormat.F32LE ->
                track.write(FloatArray(sampleCount), 0, sampleCount, AudioTrack.WRITE_BLOCKING) > 0
        }
    }

    private fun writePcmFully(
        track: AudioTrack,
        chunk: PcmChunk,
        format: AndroidAudioOutputPcmFormat,
        stats: OutputStats,
        connected: AtomicBoolean,
        session: AudioSocketSession,
        chunkAgeMs: Long,
    ): Boolean = when (format) {
        AndroidAudioOutputPcmFormat.S16LE -> writeS16LeFully(track, chunk, stats, connected, session, chunkAgeMs)
        AndroidAudioOutputPcmFormat.F32LE -> writeFloat32LeFully(track, chunk, stats, connected, session, chunkAgeMs)
    }

    private fun writeS16LeFully(
        track: AudioTrack,
        chunk: PcmChunk,
        stats: OutputStats,
        connected: AtomicBoolean,
        session: AudioSocketSession,
        chunkAgeMs: Long,
    ): Boolean {
        var offset = 0
        var noProgressWrites = 0
        while (offset < chunk.length && running && session.running && connected.get()) {
            val remaining = chunk.length - offset
            val beforeWrite = System.nanoTime()
            val written = track.write(chunk.bytes, offset, remaining, AudioTrack.WRITE_BLOCKING)
            val writeMs = elapsedMsSince(beforeWrite)
            when {
                written > 0 -> {
                    if (written < remaining) stats.recordPartialWriteCall()
                    stats.recordWrite(written, chunk.peak, chunkAgeMs, writeMs)
                    offset += written
                    noProgressWrites = 0
                }
                written == 0 -> {
                    noProgressWrites += 1
                    stats.recordZeroWrite(writeMs)
                    if (noProgressWrites >= 3) {
                        stats.recordDroppedRemainder(chunk.length - offset)
                        session.recordFailure(AudioRouteState.FAILED, "AudioTrack int16 output stopped making progress")
                        return false
                    }
                    Thread.sleep(1)
                }
                else -> {
                    stats.recordWriteError()
                    stats.recordDroppedRemainder(chunk.length - offset)
                    LogBus.w(TAG, "AudioTrack int16 write failed with code $written after ${offset}/${chunk.length} bytes")
                    session.recordFailure(AudioRouteState.FAILED, "AudioTrack int16 write failed with code $written")
                    return false
                }
            }
        }
        if (offset < chunk.length) {
            stats.recordDroppedRemainder(chunk.length - offset)
            return false
        }
        return true
    }

    private fun writeFloat32LeFully(
        track: AudioTrack,
        chunk: PcmChunk,
        stats: OutputStats,
        connected: AtomicBoolean,
        session: AudioSocketSession,
        chunkAgeMs: Long,
    ): Boolean {
        val samples = float32LeToFloatArray(chunk.bytes, chunk.length)
        var sampleOffset = 0
        var noProgressWrites = 0
        while (sampleOffset < samples.size && running && session.running && connected.get()) {
            val remainingSamples = samples.size - sampleOffset
            val beforeWrite = System.nanoTime()
            val writtenSamples = track.write(samples, sampleOffset, remainingSamples, AudioTrack.WRITE_BLOCKING)
            val writeMs = elapsedMsSince(beforeWrite)
            when {
                writtenSamples > 0 -> {
                    if (writtenSamples < remainingSamples) stats.recordPartialWriteCall()
                    stats.recordWrite(writtenSamples * AndroidAudioOutputPcmFormat.F32LE.bytesPerSample, chunk.peak, chunkAgeMs, writeMs)
                    sampleOffset += writtenSamples
                    noProgressWrites = 0
                }
                writtenSamples == 0 -> {
                    noProgressWrites += 1
                    stats.recordZeroWrite(writeMs)
                    if (noProgressWrites >= 3) {
                        stats.recordDroppedRemainder((samples.size - sampleOffset) * AndroidAudioOutputPcmFormat.F32LE.bytesPerSample)
                        session.recordFailure(AudioRouteState.FAILED, "AudioTrack float32 output stopped making progress")
                        return false
                    }
                    Thread.sleep(1)
                }
                else -> {
                    stats.recordWriteError()
                    stats.recordDroppedRemainder((samples.size - sampleOffset) * AndroidAudioOutputPcmFormat.F32LE.bytesPerSample)
                    LogBus.w(TAG, "AudioTrack float32 write failed with code $writtenSamples after ${sampleOffset}/${samples.size} samples")
                    session.recordFailure(AudioRouteState.FAILED, "AudioTrack float32 write failed with code $writtenSamples")
                    return false
                }
            }
        }
        if (sampleOffset < samples.size) {
            stats.recordDroppedRemainder((samples.size - sampleOffset) * AndroidAudioOutputPcmFormat.F32LE.bytesPerSample)
            return false
        }
        return true
    }

    private fun currentInputDevices(context: Context): List<AudioDeviceInfo> =
        context.applicationContext.getSystemService(AudioManager::class.java)
            ?.getDevices(AudioManager.GET_DEVICES_INPUTS)
            .orEmpty()
            .filter { AndroidAudioDevicePolicy.classify(it.toDescriptor("input")) != null }

    private fun currentOutputDevices(context: Context): List<AudioDeviceInfo> =
        context.applicationContext.getSystemService(AudioManager::class.java)
            ?.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .orEmpty()
            .filter { AndroidAudioDevicePolicy.classify(it.toDescriptor("output")) != null }

    private fun chooseRecordFormat(device: AudioDeviceInfo?): AudioFormatChoice {
        val rates = device?.sampleRates?.takeIf { it.isNotEmpty() } ?: sampleRates
        val targetMs = bufferTargetMs()
        for (rate in sampleRates.filter { it in rates }.ifEmpty { rates.toList() }) {
            val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            if (min > 0) return AudioFormatChoice(rate, bufferSizeFor(rate, min, targetMs), targetMs)
        }
        error("No supported input format")
    }

    private fun choosePlaybackRate(device: AudioDeviceInfo?): Int {
        val rates = device?.sampleRates?.takeIf { it.isNotEmpty() } ?: sampleRates
        return sampleRates.firstOrNull { it in rates } ?: rates.first()
    }

    private fun outputAudioAttributes(): AudioAttributes {
        return AudioAttributes.Builder()
            // Radio monitor audio is playback, not a phone/VoIP communication stream.
            // Communication usage lets the platform route to the earpiece on some OEMs.
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
    }

    private fun AudioDeviceInfo.toDescriptor(direction: String) = AndroidAudioDeviceDescriptor(
        androidDeviceId = id,
        type = type,
        isSource = isSource,
        isSink = isSink,
        direction = direction,
        productName = productName?.toString()?.takeIf { it.isNotBlank() } ?: fallbackDeviceName(direction),
        address = address.orEmpty(),
        sampleRates = sampleRates.toList(),
        channelCounts = channelCounts.toList(),
    )

    private fun AudioDeviceInfo.toBridgeDevice(direction: String): AndroidAudioEndpoint? =
        AndroidAudioDevicePolicy.classify(toDescriptor(direction))

    private fun uniqueBridgeDevices(devices: List<AndroidAudioEndpoint>): List<AndroidAudioEndpoint> {
        val identified = AndroidAudioDevicePolicy.makeIdentitiesUnique(devices)
        val counts = mutableMapOf<String, Int>()
        return identified.map { device ->
            val base = "[Android] ${device.name}"
            val count = (counts[base] ?: 0) + 1
            counts[base] = count
            device.copy(name = if (count == 1) base else "$base #$count")
        }
    }

    private fun androidSocketFile(prootPath: String): File =
        File(BridgeRuntime.paths.socketsDir, prootPath.substringAfterLast('/'))

    private fun writeDevicesFile(target: File, inputs: List<AndroidAudioEndpoint>, outputs: List<AndroidAudioEndpoint>) {
        val inputDefault = AndroidAudioDevicePolicy.defaultEndpoint(inputs, "input")
        val outputDefault = AndroidAudioDevicePolicy.defaultEndpoint(outputs, "output")
        fun toJson(device: AndroidAudioEndpoint, isDefault: Boolean): JSONObject {
            val json = JSONObject()
            AndroidAudioDevicePolicy.manifestFields(device, isDefault).forEach { (key, value) ->
                if (value != null) json.put(key, value)
            }
            return json
                .put("channels", 1)
                .put("sampleRate", AndroidAudioDevicePolicy.preferredSampleRate(device.sampleRates))
                .put("sampleRates", JSONArray(device.sampleRates.ifEmpty { sampleRates.toList() }))
                .put("format", "s16le")
                .put("formats", JSONArray(if (device.direction == "output") listOf("s16le", "f32le") else listOf("s16le")))
                .put("capabilities", JSONObject().also { capabilities ->
                    AndroidAudioDevicePolicy.capabilityFields(device).forEach { (key, value) ->
                        when (value) {
                            null -> Unit
                            is Collection<*> -> capabilities.put(key, JSONArray(value))
                            else -> capabilities.put(key, value)
                        }
                    }
                })
        }
        val root = JSONObject()
            .put("schemaVersion", 2)
            .put("updatedAt", System.currentTimeMillis())
            .put("socketDir", "/opt/tx5dr-data/runtime/sockets")
            .put("format", "s16le")
            .put("formats", JSONArray(listOf("s16le", "f32le")))
            .put("channels", 1)
            .put("bufferTargetMs", bufferTargetMs())
        val inputArray = JSONArray()
        inputs.forEach { inputArray.put(toJson(it, it.routeKey == inputDefault?.routeKey)) }
        val outputArray = JSONArray()
        outputs.forEach { outputArray.put(toJson(it, it.routeKey == outputDefault?.routeKey)) }
        root.put("inputDevices", inputArray)
        root.put("outputDevices", outputArray)
        runCatching {
            target.parentFile?.mkdirs()
            val temp = File(target.parentFile, ".${target.name}.tmp")
            temp.writeText(root.toString(2))
            if (!temp.renameTo(target)) {
                target.writeText(temp.readText())
                temp.delete()
            }
        }.onFailure { error -> LogBus.w(TAG, "Failed to write audio devices file: ${error.message}") }
    }

    private fun AudioDeviceInfo.fallbackDeviceName(direction: String): String = when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_MIC -> "Phone microphone"
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "Phone speaker"
        AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET -> "USB Audio $id"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Wired headset"
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "Wired headphones"
        AudioDeviceInfo.TYPE_LINE_ANALOG, AudioDeviceInfo.TYPE_AUX_LINE -> "Analog line ${if (direction == "input") "input" else "output"}"
        else -> "Audio device $id"
    }

    private fun AudioDeviceInfo.describe(): String = "${fallbackDeviceName(if (isSource) "input" else "output")}(id=$id,type=$type,name=${productName ?: "unknown"},address=$address)"

    private fun buildRecorder(format: AudioFormat, bufferSize: Int, device: AudioDeviceInfo): AudioRecordBuild {
        val kind = device.toBridgeDevice("input")?.kind ?: AudioRoute.USB
        val sources = AndroidAudioDevicePolicy.captureProfiles(kind).map { profile ->
            when (profile) {
                AndroidAudioCaptureProfile.UNPROCESSED -> MediaRecorder.AudioSource.UNPROCESSED
                AndroidAudioCaptureProfile.VOICE_RECOGNITION -> MediaRecorder.AudioSource.VOICE_RECOGNITION
                AndroidAudioCaptureProfile.DEFAULT -> MediaRecorder.AudioSource.DEFAULT
            }
        }
        var lastError: Throwable? = null
        for (source in sources.distinct()) {
            var recorder: AudioRecord? = null
            try {
                recorder = AudioRecord.Builder()
                    .setAudioSource(source)
                    .setAudioFormat(format)
                    .setBufferSizeInBytes(bufferSize)
                    .build()
                if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                    throw IllegalStateException("AudioRecord failed to initialize with source=${sourceName(source)}")
                }
                if (!recorder.setPreferredDevice(device)) {
                    throw IllegalStateException("Android rejected the input route with source=${sourceName(source)}")
                }
                recorder.startRecording()
                val routed = awaitRoutedDevice(recorder)
                if (routed == null || !sameAudioDevice(routed, device)) {
                    throw IllegalStateException(
                        "Input route mismatch with source=${sourceName(source)} expected=${device.describe()} actual=${routed?.describe() ?: "none"}",
                    )
                }
                if (!running) throw IllegalStateException("Android audio bridge stopped while opening input")
                return AudioRecordBuild(recorder, source)
            } catch (error: Throwable) {
                lastError = error
                runCatching { recorder?.stop() }
                runCatching { recorder?.release() }
                LogBus.w(TAG, "Android input source ${sourceName(source)} unavailable: ${error.message}")
            }
        }
        lastError?.let { throw it }
        error("AudioRecord failed to initialize")
    }

    private fun sourceName(source: Int): String = when (source) {
        MediaRecorder.AudioSource.UNPROCESSED -> "UNPROCESSED"
        MediaRecorder.AudioSource.VOICE_RECOGNITION -> "VOICE_RECOGNITION"
        MediaRecorder.AudioSource.DEFAULT -> "DEFAULT"
        else -> source.toString()
    }

    private fun update(reducer: (AndroidAudioStatus) -> AndroidAudioStatus) {
        val applyUpdate = { updateNow(reducer(status)) }
        if (Thread.currentThread() === coordinatorThread) applyUpdate()
        else coordinator.execute { applyUpdate() }
    }

    private fun updateNow(next: AndroidAudioStatus) {
        status = next
        listeners.forEach { listener ->
            runCatching { listener(next) }.onFailure { Log.w(TAG, "Android audio listener failed", it) }
        }
        devicesFile?.let { writeDevicesFile(it, next.inputDevices, next.outputDevices) }
        LogBus.i(TAG, "Android audio state=${next.state}, inputs=${next.inputDevices.size}, outputs=${next.outputDevices.size}${next.error?.let { ", error=$it" } ?: ""}")
    }

    private fun markEndpointState(
        routeKey: String,
        routeState: String,
        clientConnected: Boolean,
        routeVerified: Boolean = false,
        failureReason: String? = null,
        audioSource: String? = null,
        session: AudioSocketSession? = null,
    ) {
        val applyState = applyState@{
            val currentGeneration = sessions[routeKey]?.generation
            if (session != null && !isCurrentAudioSessionGeneration(session.generation, currentGeneration)) {
                return@applyState
            }
            fun updateEndpoint(endpoint: AndroidAudioEndpoint): AndroidAudioEndpoint = if (endpoint.routeKey == routeKey) {
                endpoint.copy(
                    clientConnected = clientConnected,
                    routeState = routeState,
                    routeVerified = routeVerified,
                    failureReason = failureReason,
                    audioSource = audioSource ?: endpoint.audioSource,
                )
            } else endpoint
            updateNow(status.copy(
                inputDevices = status.inputDevices.map(::updateEndpoint),
                outputDevices = status.outputDevices.map(::updateEndpoint),
            ))
        }
        if (Thread.currentThread() === coordinatorThread) applyState() else coordinator.execute { applyState() }
    }

    private fun markEndpointFailure(routeKey: String, routeState: String, reason: String, session: AudioSocketSession) {
        coordinator.execute {
            markEndpointState(routeKey, routeState, clientConnected = false, routeVerified = false, failureReason = reason, session = session)
        }
    }

    private fun markEndpointDisconnected(routeKey: String, session: AudioSocketSession) {
        coordinator.execute {
            if (!isCurrentAudioSessionGeneration(session.generation, sessions[routeKey]?.generation)) return@execute
            val endpoint = (status.inputDevices + status.outputDevices).firstOrNull { it.routeKey == routeKey }
            if (endpoint?.available == false) return@execute
            val failure = session.failure()
            if (failure == null) {
                markEndpointState(routeKey, AudioRouteState.IDLE, clientConnected = false, session = session)
            } else {
                markEndpointState(routeKey, failure.routeState, clientConnected = false, failureReason = failure.reason, session = session)
            }
        }
    }

    private fun resetReleasedEndpointNow(routeKey: String) {
        fun reset(endpoint: AndroidAudioEndpoint) = if (endpoint.routeKey == routeKey) {
            AndroidAudioDevicePolicy.resetReleasedEndpoint(endpoint)
        } else endpoint
        updateNow(status.copy(
            inputDevices = status.inputDevices.map(::reset),
            outputDevices = status.outputDevices.map(::reset),
        ))
    }

    private fun <T> coordinated(block: () -> T): T {
        if (Thread.currentThread() === coordinatorThread) return block()
        return coordinator.submit<T> { block() }.get()
    }

    private fun registerNoisyReceiver(context: Context) {
        if (noisyReceiverRegistered) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context?, intent: Intent?) {
                if (intent?.action != AudioManager.ACTION_AUDIO_BECOMING_NOISY) return
                coordinator.execute {
                    sessions.values.filter { session ->
                        session.device.direction == "output" &&
                            session.device.kind in setOf(AudioRoute.WIRED_HEADSET, AudioRoute.WIRED_HEADPHONES, AudioRoute.ANALOG_LINE)
                    }.forEach { session ->
                        val reason = "Physical output route became noisy or was disconnected"
                        session.failClosed(AudioRouteState.LOST, reason)
                        markEndpointState(session.device.routeKey, AudioRouteState.LOST, clientConnected = false, failureReason = reason, session = session)
                    }
                }
            }
        }
        runCatching {
            val filter = IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
            if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            else @Suppress("DEPRECATION") context.registerReceiver(receiver, filter)
            noisyReceiverRegistered = true
        }.onFailure { LogBus.w(TAG, "Unable to register audio noisy receiver: ${it.message}") }
    }

    private fun awaitRoutedDevice(routing: AudioRouting, timeoutMs: Long = 750): AudioDeviceInfo? {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        var routed = routing.routedDevice
        while (routed == null && System.nanoTime() < deadline) {
            Thread.sleep(10)
            routed = routing.routedDevice
        }
        return routed
    }

    private fun sameAudioDevice(actual: AudioDeviceInfo, expected: AudioDeviceInfo): Boolean = actual.id == expected.id

    private fun routedDeviceName(routing: AudioRouting): String = routing.routedDevice?.describe() ?: "none"

    private class AudioRouteException(val routeState: String, message: String) : IllegalStateException(message)

    private data class SessionFailure(val routeState: String, val reason: String)

    private fun elapsedMsSince(startNanos: Long): Double = (System.nanoTime() - startNanos) / 1_000_000.0

    private fun bufferTargetMs(): Int = BridgeRuntime.getAudioBufferTargetMs()

    internal fun bufferSizeFor(sampleRate: Int, minBuffer: Int, targetMs: Int, bytesPerSample: Int = 2): Int =
        maxOf(minBuffer, sampleRate * targetMs / 1000 * bytesPerSample)

    private fun setAudioThreadPriority(label: String) {
        runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO) }
            .onFailure { LogBus.w(TAG, "Unable to set audio thread priority for $label: ${it.message}") }
    }

    private fun readExactly(input: InputStream, length: Int): ByteArray? {
        val buffer = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val n = input.read(buffer, offset, length - offset)
            if (n <= 0) return null
            offset += n
        }
        return buffer
    }

    private fun peakAbsPcm(buffer: ByteArray, length: Int, format: AndroidAudioOutputPcmFormat): Int = when (format) {
        AndroidAudioOutputPcmFormat.S16LE -> peakAbsPcm16(buffer, length)
        AndroidAudioOutputPcmFormat.F32LE -> peakAbsFloat32(buffer, length)
    }

    private fun peakAbsPcm16(buffer: ByteArray, length: Int): Int {
        var peak = 0
        var offset = 0
        while (offset + 1 < length) {
            val sample = (buffer[offset].toInt() and 0xff) or (buffer[offset + 1].toInt() shl 8)
            val signed = sample.toShort().toInt()
            val absValue = if (signed == Short.MIN_VALUE.toInt()) 32768 else abs(signed)
            if (absValue > peak) peak = absValue
            offset += 2
        }
        return peak
    }

    private fun float32LeToFloatArray(buffer: ByteArray, length: Int): FloatArray {
        val samples = FloatArray(length / AndroidAudioOutputPcmFormat.F32LE.bytesPerSample)
        var offset = 0
        var index = 0
        while (offset + 3 < length) {
            val bits = (buffer[offset].toInt() and 0xff) or
                ((buffer[offset + 1].toInt() and 0xff) shl 8) or
                ((buffer[offset + 2].toInt() and 0xff) shl 16) or
                ((buffer[offset + 3].toInt() and 0xff) shl 24)
            samples[index++] = java.lang.Float.intBitsToFloat(bits)
            offset += 4
        }
        return samples
    }

    private fun peakAbsFloat32(buffer: ByteArray, length: Int): Int {
        var peak = 0
        var offset = 0
        while (offset + 3 < length) {
            val bits = (buffer[offset].toInt() and 0xff) or
                ((buffer[offset + 1].toInt() and 0xff) shl 8) or
                ((buffer[offset + 2].toInt() and 0xff) shl 16) or
                ((buffer[offset + 3].toInt() and 0xff) shl 24)
            val sample = java.lang.Float.intBitsToFloat(bits)
            val absValue = if (sample.isFinite()) abs(sample).coerceAtMost(1f) else 0f
            val scaled = (absValue * 32767f).roundToInt()
            if (scaled > peak) peak = scaled
            offset += 4
        }
        return peak
    }

    private data class AudioFormatChoice(val sampleRate: Int, val bufferSize: Int, val bufferTargetMs: Int)

    private data class AudioRecordBuild(val recorder: AudioRecord, val audioSource: Int)

    private class AudioSocketSession(val device: AndroidAudioEndpoint, val generation: Long) {
        @Volatile var running = true
        @Volatile var server: AndroidUnixSocketServer? = null
        @Volatile var thread: Thread? = null
        private val failure = AtomicReference<SessionFailure?>(null)
        private val failAction = AtomicReference<(() -> Unit)?>(null)
        val isAlive: Boolean get() = running && thread?.isAlive == true

        fun setFailAction(action: () -> Unit) {
            failAction.set(action)
        }

        fun clearFailAction() {
            failAction.set(null)
        }

        fun clearFailure() {
            failure.set(null)
        }

        fun recordFailure(routeState: String, reason: String) {
            failure.compareAndSet(null, SessionFailure(routeState, reason))
        }

        fun failClosed(routeState: String, reason: String) {
            recordFailure(routeState, reason)
            runCatching { failAction.getAndSet(null)?.invoke() }
        }

        fun failure(): SessionFailure? = failure.get()

        fun stop() {
            running = false
            runCatching { failAction.getAndSet(null)?.invoke() }
            runCatching { server?.close() }
            if (Thread.currentThread() != thread) thread?.join(1500)
        }
    }

    private fun fmtMs(value: Double): String = String.format(Locale.US, "%.2f", value)

    private fun formatJitter(prefix: String, snapshot: PcmJitterSnapshot): String {
        val watermarks = snapshot.watermarks
        return "$prefix.targetMs=${watermarks.targetMs} $prefix.effectiveTargetMs=${watermarks.effectiveTargetMs} " +
            "$prefix.bufferedMs=${fmtMs(snapshot.bufferedMs)} $prefix.chunks=${snapshot.chunks} " +
            "$prefix.lowWaterMs=${fmtMs(watermarks.lowWaterMs)} $prefix.highWaterMs=${fmtMs(watermarks.highWaterMs)} " +
            "$prefix.hardMaxMs=${fmtMs(watermarks.hardMaxMs)} $prefix.dropToMs=${fmtMs(watermarks.dropToMs)} " +
            "$prefix.startWaitCount=${snapshot.startWaitCount} $prefix.rebufferCount=${snapshot.rebufferCount} " +
            "$prefix.underrunCount=${snapshot.underrunCount} $prefix.overflowDropBytes=${snapshot.overflowDropBytes} " +
            "$prefix.overflowDropChunks=${snapshot.overflowDropChunks} $prefix.fastForwardCount=${snapshot.fastForwardCount} " +
            snapshot.bufferedLatency.format("${prefix}Buffered") + " " +
            snapshot.consumerWaitLatency.format("${prefix}ConsumerWait")
    }

    private class InputStats(
        private val sampleRate: Int,
        private val bufferSize: Int,
        private val jitterBuffer: PcmJitterBuffer,
    ) {
        private val startedAt = System.currentTimeMillis()
        private var lastLoggedAt = startedAt
        private var bytesRead = 0L
        private var bytesWritten = 0L
        private var reads = 0L
        private var writes = 0L
        private var shortReads = 0L
        private var readErrors = 0L
        private var zeroSamples = 0L
        private var totalSamples = 0L
        private var writeOverruns = 0L
        private var lastSequence = -1L
        private val readLatency = LatencyWindow()
        private val queueAgeLatency = LatencyWindow()
        private val socketWriteLatency = LatencyWindow()

        @Synchronized fun recordCaptured(buffer: ByteArray, length: Int, readMs: Double, sequence: Long) {
            reads += 1
            bytesRead += length.toLong()
            if (length < bufferSize) shortReads += 1
            lastSequence = sequence
            readLatency.record(readMs)
            var offset = 0
            while (offset + 1 < length) {
                if (buffer[offset] == 0.toByte() && buffer[offset + 1] == 0.toByte()) zeroSamples += 1
                totalSamples += 1
                offset += 2
            }
        }

        @Synchronized fun recordWritten(length: Int, chunkAgeMs: Long, socketWriteMs: Double) {
            writes += 1
            bytesWritten += length.toLong()
            queueAgeLatency.record(chunkAgeMs.toDouble())
            socketWriteLatency.record(socketWriteMs)
            if (socketWriteMs > SLOW_INPUT_WRITE_MS) writeOverruns += 1
        }

        @Synchronized fun recordReadError() {
            readErrors += 1
        }

        @Synchronized fun logIfDue(message: String) {
            val now = System.currentTimeMillis()
            if (now - lastLoggedAt < STATS_INTERVAL_MS) return
            val elapsedSeconds = ((now - startedAt).coerceAtLeast(1)).toDouble() / 1000.0
            val read = readLatency.snapshot()
            val queueAge = queueAgeLatency.snapshot()
            val socketWrite = socketWriteLatency.snapshot()
            val jitter = jitterBuffer.snapshot()
            LogBus.i(
                TAG,
                "$message rate=$sampleRate buffer=$bufferSize elapsed=${"%.1f".format(elapsedSeconds)}s bytesRead=$bytesRead bytesWritten=$bytesWritten reads=$reads writes=$writes lastSeq=$lastSequence shortReads=$shortReads readErrors=$readErrors zeroSamples=$zeroSamples/$totalSamples inputWriteOverruns=$writeOverruns ${read.format("inputRead")} ${queueAge.format("inputChunkAge")} ${socketWrite.format("inputSocketWrite")} ${formatJitter("inputJitter", jitter)}"
            )
            lastLoggedAt = now
        }
    }

    private class OutputStats(
        private val sampleRate: Int,
        private val format: String,
        private val bytesPerSample: Int,
        private val bufferSize: Int,
        private val jitterBuffer: PcmJitterBuffer,
    ) {
        private val startedAt = System.currentTimeMillis()
        private var lastLoggedAt = startedAt
        private var bytesRead = 0L
        private var socketReads = 0L
        private var shortReads = 0L
        private var activeBytesWritten = 0L
        private var activeSamplesWritten = 0L
        private var silentBytesSkipped = 0L
        private var outputDroppedRemainderBytes = 0L
        private var flushCount = 0L
        private var partialWriteCalls = 0L
        private var zeroWrites = 0L
        private var writeErrors = 0L
        private var activePeak = 0
        private var lastSequence = -1L
        private val socketReadLatency = LatencyWindow()
        private val queueAgeLatency = LatencyWindow()
        private val audioTrackWriteLatency = LatencyWindow()

        @Synchronized fun recordSocketRead(readBytes: Int, readMs: Double, sequence: Long) {
            socketReads += 1
            bytesRead += readBytes.toLong()
            if (readBytes < bufferSize) shortReads += 1
            lastSequence = sequence
            socketReadLatency.record(readMs)
        }

        @Synchronized fun recordChunkAge(chunkAgeMs: Long) {
            queueAgeLatency.record(chunkAgeMs.toDouble())
        }

        @Synchronized fun recordSilence(length: Int) {
            silentBytesSkipped += length.toLong()
        }

        @Synchronized fun recordWrite(writtenBytes: Int, peak: Int, _chunkAgeMs: Long, audioTrackWriteMs: Double) {
            activeBytesWritten += writtenBytes.toLong()
            activeSamplesWritten += (writtenBytes / bytesPerSample).toLong()
            if (peak > activePeak) activePeak = peak
            audioTrackWriteLatency.record(audioTrackWriteMs)
        }

        @Synchronized fun recordPartialWriteCall() {
            partialWriteCalls += 1
        }

        @Synchronized fun recordDroppedRemainder(droppedBytes: Int) {
            if (droppedBytes > 0) outputDroppedRemainderBytes += droppedBytes.toLong()
        }

        @Synchronized fun recordWriteError() {
            writeErrors += 1
        }

        @Synchronized fun recordZeroWrite(audioTrackWriteMs: Double) {
            zeroWrites += 1
            writeErrors += 1
            audioTrackWriteLatency.record(audioTrackWriteMs)
        }

        @Synchronized fun recordFlush() {
            flushCount += 1
        }

        @Synchronized fun logIfDue(message: String) {
            val now = System.currentTimeMillis()
            if (now - lastLoggedAt < STATS_INTERVAL_MS) return
            val elapsedSeconds = ((now - startedAt).coerceAtLeast(1)).toDouble() / 1000.0
            val socketRead = socketReadLatency.snapshot()
            val queueAge = queueAgeLatency.snapshot()
            val audioTrackWrite = audioTrackWriteLatency.snapshot()
            val jitter = jitterBuffer.snapshot()
            LogBus.i(
                TAG,
                "$message rate=$sampleRate format=$format bytesPerSample=$bytesPerSample buffer=$bufferSize elapsed=${"%.1f".format(elapsedSeconds)}s bytesRead=$bytesRead activeSamplesWritten=$activeSamplesWritten activeBytesWritten=$activeBytesWritten socketReads=$socketReads lastSeq=$lastSequence shortReads=$shortReads outputDroppedRemainderBytes=$outputDroppedRemainderBytes silentBytesSkipped=$silentBytesSkipped flushCount=$flushCount partialWriteCalls=$partialWriteCalls zeroWrites=$zeroWrites writeErrors=$writeErrors activePeak=$activePeak ${socketRead.format("outputSocketRead")} ${queueAge.format("outputChunkAge")} ${audioTrackWrite.format("outputAudioTrackWrite")} ${formatJitter("outputJitter", jitter)}"
            )
            lastLoggedAt = now
            activePeak = 0
        }
    }
}
