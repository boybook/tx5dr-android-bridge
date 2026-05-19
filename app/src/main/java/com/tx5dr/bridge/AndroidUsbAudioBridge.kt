package com.tx5dr.bridge

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.net.InetAddress
import java.net.ServerSocket
import java.util.ArrayDeque
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs

object AudioRoute {
    const val AUTO = "auto"
    const val USB = "usb"
    const val BUILTIN_MIC = "builtinMic"
    const val BUILTIN_SPEAKER = "builtinSpeaker"
}

object AndroidUsbAudioBridge {
    private const val TAG = "AudioBridge"
    private const val INPUT_PORT = 4719
    private const val OUTPUT_PORT = 4720
    private const val STATS_INTERVAL_MS = 5000L
    private const val BUFFER_TARGET_MS = 100
    private const val QUEUE_CAPACITY_CHUNKS = 2
    private const val SLOW_INPUT_WRITE_MS = 200.0
    private const val STALE_OUTPUT_CHUNK_MS = 250L
    private const val SLOW_OUTPUT_WRITE_MS = 100.0
    private const val SILENCE_FLUSH_MS = 200L
    private const val SILENCE_PEAK_THRESHOLD = 4
    private val sampleRates = intArrayOf(48000, 44100)
    private val listeners = CopyOnWriteArrayList<(UsbAudioStatus) -> Unit>()

    @Volatile private var status = UsbAudioStatus()
    @Volatile private var running = false
    private var inputThread: Thread? = null
    private var outputThread: Thread? = null
    private var inputServer: ServerSocket? = null
    private var outputServer: ServerSocket? = null
    @Volatile private var deviceCallbackRegistered = false

    fun addListener(listener: (UsbAudioStatus) -> Unit) {
        listeners.add(listener)
        runCatching { listener(status) }.onFailure { Log.w(TAG, "USB audio listener failed", it) }
    }

    fun removeListener(listener: (UsbAudioStatus) -> Unit) {
        listeners.remove(listener)
    }

    fun hasRecordPermission(context: Context): Boolean = if (Build.VERSION.SDK_INT >= 23) {
        context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    } else true

    fun refreshDevices(context: Context): UsbAudioStatus {
        val manager = context.applicationContext.getSystemService(AudioManager::class.java)
        val inputs = manager?.getDevices(AudioManager.GET_DEVICES_INPUTS).orEmpty()
            .filter { it.isSupportedInputDevice() && it.isSource }
            .map { it.toBridgeDevice("input") }
        val outputs = manager?.getDevices(AudioManager.GET_DEVICES_OUTPUTS).orEmpty()
            .filter { it.isSupportedOutputDevice() && it.isSink }
            .map { it.toBridgeDevice("output") }
        val selectedInputRoute = getSelectedInputRoute()
        val selectedOutputRoute = getSelectedOutputRoute()
        val nextState = when {
            hasRecordPermission(context) -> status.state
            status.state == "permission-denied" -> "permission-denied"
            else -> "permission-required"
        }
        update(
            status.copy(
                state = nextState,
                selectedInputRoute = selectedInputRoute,
                selectedOutputRoute = selectedOutputRoute,
                inputDevices = inputs,
                outputDevices = outputs,
                error = null,
            )
        )
        return status
    }

    fun setInputRoute(context: Context, route: String) {
        BridgeRuntime.setStringPreference(BridgeRuntime.PREF_AUDIO_INPUT_ROUTE, normalizeInputRoute(route))
        refreshDevices(context)
        restartIfRunning(context.applicationContext, "input route changed")
    }

    fun setOutputRoute(context: Context, route: String) {
        BridgeRuntime.setStringPreference(BridgeRuntime.PREF_AUDIO_OUTPUT_ROUTE, normalizeOutputRoute(route))
        refreshDevices(context)
        restartIfRunning(context.applicationContext, "output route changed")
    }

    fun startWatchingDevices(context: Context) {
        if (deviceCallbackRegistered || Build.VERSION.SDK_INT < 23) return
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
    }

    fun startIfPermitted(context: Context): Boolean {
        val refreshed = refreshDevices(context)
        if (!hasRecordPermission(context)) {
            update(refreshed.copy(state = if (refreshed.state == "permission-denied") "permission-denied" else "permission-required", error = null))
            return false
        }
        val inputSelection = selectInputDevice(context)
        val outputSelection = selectOutputDevice(context)
        if (inputSelection.device == null || outputSelection.device == null) {
            update(
                refreshed.copy(
                    state = "no-device",
                    activeInputDevice = null,
                    activeOutputDevice = null,
                    error = unavailableRouteMessage(inputSelection, outputSelection),
                )
            )
            return false
        }
        start(context, inputSelection, outputSelection)
        return true
    }

    fun start(context: Context) {
        start(context, null, null)
    }

    private fun start(context: Context, preparedInput: AudioDeviceSelection?, preparedOutput: AudioDeviceSelection?) {
        val app = context.applicationContext
        if (!hasRecordPermission(app)) {
            update(status.copy(state = "permission-required", error = "RECORD_AUDIO permission is required"))
            return
        }
        if (running) return
        val refreshed = refreshDevices(app)
        val inputSelection = preparedInput ?: selectInputDevice(app)
        val outputSelection = preparedOutput ?: selectOutputDevice(app)
        if (inputSelection.device == null || outputSelection.device == null) {
            update(
                refreshed.copy(
                    state = "no-device",
                    activeInputDevice = null,
                    activeOutputDevice = null,
                    error = unavailableRouteMessage(inputSelection, outputSelection),
                )
            )
            return
        }
        running = true
        update(
            status.copy(
                state = "starting",
                activeInputDevice = inputSelection.device.toBridgeDevice("input"),
                activeOutputDevice = outputSelection.device.toBridgeDevice("output"),
                error = null,
            )
        )
        inputThread = Thread { inputLoop(app, inputSelection) }.also { it.name = "tx5dr-audio-input"; it.start() }
        outputThread = Thread { outputLoop(app, outputSelection) }.also { it.name = "tx5dr-audio-output"; it.start() }
        BridgeRuntime.startLinuxAudioSide()
    }

    fun markPermissionDenied() {
        update(status.copy(state = "permission-denied", error = null))
    }

    fun stop() {
        stop(stopLinuxSide = true)
    }

    private fun stop(stopLinuxSide: Boolean) {
        running = false
        try { inputServer?.close() } catch (_: Throwable) {}
        try { outputServer?.close() } catch (_: Throwable) {}
        if (stopLinuxSide) BridgeRuntime.stopLinuxAudioSide()
        inputThread?.join(1500)
        outputThread?.join(1500)
        inputThread = null
        outputThread = null
        update(status.copy(state = "stopped", activeInputDevice = null, activeOutputDevice = null))
    }

    private fun handleDeviceChange(context: Context, reason: String, devices: Array<out AudioDeviceInfo>) {
        val audioChanged = devices.any { it.isSupportedInputDevice() || it.isSupportedOutputDevice() }
        if (!audioChanged) return
        val shouldRestart = shouldRestartForDeviceChange(devices)
        LogBus.i(TAG, "Audio device $reason")
        refreshDevices(context)
        if (shouldRestart) {
            Thread {
                runCatching {
                    stop(stopLinuxSide = false)
                    startIfPermitted(context)
                }.onFailure { error ->
                    LogBus.e(TAG, "Audio hotplug restart failed", error)
                    update(status.copy(state = "error", error = error.message))
                }
            }.also { it.name = "tx5dr-audio-hotplug"; it.start() }
        } else if (BridgeRuntime.getPreference(BridgeRuntime.PREF_AUTO_START_BRIDGES, true)) {
            runCatching { startIfPermitted(context) }.onFailure { error ->
                LogBus.e(TAG, "Audio hotplug autostart failed", error)
                update(status.copy(state = "error", error = error.message))
            }
        }
    }

    private fun restartIfRunning(context: Context, reason: String) {
        if (!running) return
        Thread {
            LogBus.i(TAG, "Restarting audio bridge: $reason")
            runCatching {
                stop(stopLinuxSide = false)
                startIfPermitted(context)
            }.onFailure { error ->
                LogBus.e(TAG, "Audio route restart failed", error)
                update(status.copy(state = "error", error = error.message))
            }
        }.also { it.name = "tx5dr-audio-route-restart"; it.start() }
    }

    @SuppressLint("MissingPermission")
    private fun inputLoop(context: Context, selection: AudioDeviceSelection) {
        var recorder: AudioRecord? = null
        try {
            val device = selection.device ?: error("Selected input audio route is unavailable")
            val formatAndBuffer = chooseRecordFormat(device)
            val format = AudioFormat.Builder()
                .setSampleRate(formatAndBuffer.sampleRate)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                .build()
            val recordBuild = buildRecorder(format, formatAndBuffer.bufferSize, device)
            recorder = recordBuild.recorder
            if (Build.VERSION.SDK_INT >= 23) recorder.preferredDevice = device
            require(recorder.state == AudioRecord.STATE_INITIALIZED) { "AudioRecord failed to initialize" }
            ServerSocket(INPUT_PORT, 1, InetAddress.getByName("127.0.0.1")).use { server ->
                inputServer = server
                LogBus.i(TAG, "Audio input waiting on 127.0.0.1:$INPUT_PORT selectedRoute=${selection.route} activeDevice=${device.describe()} source=${sourceName(recordBuild.audioSource)} rate=${formatAndBuffer.sampleRate} buffer=${formatAndBuffer.bufferSize}")
                server.accept().use { socket ->
                    socket.tcpNoDelay = true
                    socket.sendBufferSize = formatAndBuffer.bufferSize * 4
                    LogBus.i(TAG, "Linux Pulse input injector connected")
                    val queue = LatestPcmQueue(QUEUE_CAPACITY_CHUNKS)
                    val connected = AtomicBoolean(true)
                    val captureError = AtomicReference<Throwable?>(null)
                    val stats = InputStats(formatAndBuffer.sampleRate, formatAndBuffer.bufferSize, queue)
                    val captureThread = Thread {
                        val buffer = ByteArray(formatAndBuffer.bufferSize)
                        var sequence = 0L
                        try {
                            recorder.startRecording()
                            while (running && connected.get() && !Thread.currentThread().isInterrupted) {
                                val n = recorder.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
                                if (n > 0) {
                                    stats.recordCaptured(buffer, n)
                                    queue.offer(PcmChunk(buffer.copyOf(n), n, System.currentTimeMillis(), sequence++, peakAbsPcm16(buffer, n)))
                                } else {
                                    stats.recordReadError()
                                }
                                stats.logIfDue("USB audio input stats")
                            }
                        } catch (error: Throwable) {
                            captureError.set(error)
                        } finally {
                            queue.close()
                        }
                    }.also { it.name = "tx5dr-usb-audio-input-capture"; it.start() }

                    update(status.copy(state = "streaming"))
                    val out = socket.getOutputStream()
                    try {
                        while (running && connected.get() && !socket.isClosed) {
                            val chunk = queue.takeLatest(500) ?: continue
                            val ageMs = System.currentTimeMillis() - chunk.createdAtMs
                            val beforeWrite = System.nanoTime()
                            out.write(chunk.bytes, 0, chunk.length)
                            out.flush()
                            val writeMs = elapsedMsSince(beforeWrite)
                            stats.recordWritten(chunk.length, ageMs, writeMs)
                            stats.logIfDue("USB audio input stats")
                        }
                    } finally {
                        connected.set(false)
                        queue.close()
                        try { socket.close() } catch (_: Throwable) {}
                        captureThread.join(1000)
                        captureError.get()?.let { throw it }
                    }
                }
            }
        } catch (error: Throwable) {
            if (running) {
                LogBus.e(TAG, "USB audio input bridge failed", error)
                update(status.copy(state = "error", error = error.message))
            }
        } finally {
            try { recorder?.stop() } catch (_: Throwable) {}
            recorder?.release()
            inputServer = null
        }
    }

    private fun outputLoop(context: Context, selection: AudioDeviceSelection) {
        var track: AudioTrack? = null
        try {
            val device = selection.device ?: error("Selected output audio route is unavailable")
            val rate = choosePlaybackRate(device)
            val minBuffer = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val bufferSize = maxOf(minBuffer, rate * BUFFER_TARGET_MS / 1000 * 2)
            val format = AudioFormat.Builder()
                .setSampleRate(rate)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build()
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
            val trackBuilder = AudioTrack.Builder()
                .setAudioAttributes(attrs)
                .setAudioFormat(format)
                .setBufferSizeInBytes(bufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
            if (Build.VERSION.SDK_INT >= 26) {
                trackBuilder.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            }
            track = trackBuilder.build()
            if (Build.VERSION.SDK_INT >= 23) track.preferredDevice = device
            require(track.state == AudioTrack.STATE_INITIALIZED) { "AudioTrack failed to initialize" }
            ServerSocket(OUTPUT_PORT, 1, InetAddress.getByName("127.0.0.1")).use { server ->
                outputServer = server
                LogBus.i(TAG, "Audio output waiting on 127.0.0.1:$OUTPUT_PORT selectedRoute=${selection.route} activeDevice=${device.describe()} rate=$rate buffer=$bufferSize")
                server.accept().use { socket ->
                    socket.tcpNoDelay = true
                    socket.receiveBufferSize = bufferSize * 4
                    LogBus.i(TAG, "Linux Pulse output capture connected")
                    val queue = LatestPcmQueue(QUEUE_CAPACITY_CHUNKS)
                    val connected = AtomicBoolean(true)
                    val readerError = AtomicReference<Throwable?>(null)
                    val stats = OutputStats(rate, bufferSize, queue)
                    val readerThread = Thread {
                        val buffer = ByteArray(bufferSize)
                        var sequence = 0L
                        try {
                            val input = socket.getInputStream()
                            while (running && connected.get() && !Thread.currentThread().isInterrupted) {
                                val n = input.read(buffer)
                                if (n <= 0) break
                                stats.recordSocketRead(n)
                                queue.offer(PcmChunk(buffer.copyOf(n), n, System.currentTimeMillis(), sequence++, peakAbsPcm16(buffer, n)))
                                stats.logIfDue("USB audio output stats")
                            }
                        } catch (error: Throwable) {
                            if (running && connected.get()) readerError.set(error)
                        } finally {
                            queue.close()
                        }
                    }.also { it.name = "tx5dr-usb-audio-output-reader"; it.start() }

                    var active = false
                    var silentSinceMs: Long? = null
                    var consecutiveZeroWrites = 0
                    try {
                        while (running && connected.get() && !socket.isClosed) {
                            val chunk = queue.takeLatest(500) ?: continue
                            val now = System.currentTimeMillis()
                            val ageMs = now - chunk.createdAtMs
                            if (ageMs > STALE_OUTPUT_CHUNK_MS) {
                                stats.recordDroppedStale(chunk.length, ageMs)
                                stats.logIfDue("USB audio output stats")
                                continue
                            }
                            stats.recordChunkAge(ageMs)
                            if (chunk.peak <= SILENCE_PEAK_THRESHOLD) {
                                stats.recordSilence(chunk.length)
                                if (active) {
                                    val started = silentSinceMs ?: now.also { silentSinceMs = it }
                                    if (now - started >= SILENCE_FLUSH_MS) {
                                        pauseFlush(track)
                                        active = false
                                        consecutiveZeroWrites = 0
                                        stats.recordFlush()
                                    }
                                }
                                stats.logIfDue("USB audio output stats")
                                continue
                            }

                            silentSinceMs = null
                            if (!active) {
                                pauseFlush(track)
                                track.play()
                                active = true
                                consecutiveZeroWrites = 0
                                stats.recordFlush()
                            }

                            val beforeWrite = System.nanoTime()
                            val written = track.write(chunk.bytes, 0, chunk.length, AudioTrack.WRITE_NON_BLOCKING)
                            val writeMs = elapsedMsSince(beforeWrite)
                            if (written > 0) {
                                consecutiveZeroWrites = 0
                                if (written < chunk.length) stats.recordPartialWrite(chunk.length - written)
                                stats.recordWrite(written, chunk.peak, ageMs, writeMs)
                            } else {
                                consecutiveZeroWrites += 1
                                stats.recordZeroWrite(writeMs)
                            }
                            if (writeMs > SLOW_OUTPUT_WRITE_MS || consecutiveZeroWrites >= 3) {
                                pauseFlush(track)
                                active = false
                                silentSinceMs = null
                                consecutiveZeroWrites = 0
                                stats.recordFlush()
                            }
                            stats.logIfDue("USB audio output stats")
                        }
                    } finally {
                        connected.set(false)
                        queue.close()
                        try { socket.close() } catch (_: Throwable) {}
                        readerThread.join(1000)
                        readerError.get()?.let { throw it }
                    }
                }
            }
        } catch (error: Throwable) {
            if (running) {
                LogBus.e(TAG, "USB audio output bridge failed", error)
                update(status.copy(state = "error", error = error.message))
            }
        } finally {
            try { track?.pause() } catch (_: Throwable) {}
            track?.release()
            outputServer = null
        }
    }

    private fun pauseFlush(track: AudioTrack) {
        try { track.pause() } catch (_: Throwable) {}
        try { track.flush() } catch (_: Throwable) {}
    }

    private fun selectInputDevice(context: Context): AudioDeviceSelection {
        val route = getSelectedInputRoute()
        val devices = context.applicationContext
            .getSystemService(AudioManager::class.java)
            ?.getDevices(AudioManager.GET_DEVICES_INPUTS)
            .orEmpty()
            .filter { it.isSupportedInputDevice() && it.isSource }
        val device = when (route) {
            AudioRoute.USB -> devices.firstOrNull { it.isUsbAudioDevice() }
            AudioRoute.BUILTIN_MIC -> devices.firstOrNull { it.isBuiltinMicDevice() }
            else -> devices.firstOrNull { it.isUsbAudioDevice() } ?: devices.firstOrNull { it.isBuiltinMicDevice() }
        }
        return AudioDeviceSelection(route, device)
    }

    private fun selectOutputDevice(context: Context): AudioDeviceSelection {
        val route = getSelectedOutputRoute()
        val devices = context.applicationContext
            .getSystemService(AudioManager::class.java)
            ?.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .orEmpty()
            .filter { it.isSupportedOutputDevice() && it.isSink }
        val device = when (route) {
            AudioRoute.USB -> devices.firstOrNull { it.isUsbAudioDevice() }
            AudioRoute.BUILTIN_SPEAKER -> devices.firstOrNull { it.isBuiltinSpeakerDevice() }
            else -> devices.firstOrNull { it.isUsbAudioDevice() } ?: devices.firstOrNull { it.isBuiltinSpeakerDevice() }
        }
        return AudioDeviceSelection(route, device)
    }

    private fun chooseRecordFormat(device: AudioDeviceInfo?): AudioFormatChoice {
        val rates = device?.sampleRates?.takeIf { it.isNotEmpty() } ?: sampleRates
        for (rate in sampleRates.filter { it in rates }.ifEmpty { rates.toList() }) {
            val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            if (min > 0) return AudioFormatChoice(rate, maxOf(min, rate * BUFFER_TARGET_MS / 1000 * 2))
        }
        error("No supported input format")
    }

    private fun choosePlaybackRate(device: AudioDeviceInfo?): Int {
        val rates = device?.sampleRates?.takeIf { it.isNotEmpty() } ?: sampleRates
        return sampleRates.firstOrNull { it in rates } ?: rates.first()
    }

    private fun AudioDeviceInfo.isSupportedInputDevice(): Boolean = isUsbAudioDevice() || isBuiltinMicDevice()

    private fun AudioDeviceInfo.isSupportedOutputDevice(): Boolean = isUsbAudioDevice() || isBuiltinSpeakerDevice()

    private fun AudioDeviceInfo.isUsbAudioDevice(): Boolean = type == AudioDeviceInfo.TYPE_USB_DEVICE || type == AudioDeviceInfo.TYPE_USB_HEADSET

    private fun AudioDeviceInfo.isBuiltinMicDevice(): Boolean = type == AudioDeviceInfo.TYPE_BUILTIN_MIC

    private fun AudioDeviceInfo.isBuiltinSpeakerDevice(): Boolean =
        type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER ||
            (Build.VERSION.SDK_INT >= 31 && type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE)

    private fun AudioDeviceInfo.toBridgeDevice(direction: String): UsbAudioDevice = UsbAudioDevice(
        id = id,
        direction = direction,
        kind = when {
            isUsbAudioDevice() -> AudioRoute.USB
            isBuiltinMicDevice() -> AudioRoute.BUILTIN_MIC
            isBuiltinSpeakerDevice() -> AudioRoute.BUILTIN_SPEAKER
            else -> "unknown"
        },
        type = type,
        name = productName?.toString()?.takeIf { it.isNotBlank() } ?: fallbackDeviceName(),
        sampleRates = sampleRates.toList(),
        channelCounts = channelCounts.toList(),
    )

    private fun AudioDeviceInfo.fallbackDeviceName(): String = when {
        isBuiltinMicDevice() -> "Phone microphone"
        isBuiltinSpeakerDevice() -> "Phone speaker"
        isUsbAudioDevice() -> "USB Audio $id"
        else -> "Audio device $id"
    }

    private fun AudioDeviceInfo.describe(): String = "${fallbackDeviceName()}(id=$id,type=$type,name=${productName ?: "unknown"})"

    private fun buildRecorder(format: AudioFormat, bufferSize: Int, device: AudioDeviceInfo): AudioRecordBuild {
        val sources = if (device.isBuiltinMicDevice()) {
            listOf(MediaRecorder.AudioSource.UNPROCESSED, MediaRecorder.AudioSource.VOICE_RECOGNITION, MediaRecorder.AudioSource.DEFAULT)
        } else {
            listOf(MediaRecorder.AudioSource.DEFAULT)
        }
        var lastError: Throwable? = null
        for (source in sources.distinct()) {
            try {
                val recorder = AudioRecord.Builder()
                    .setAudioSource(source)
                    .setAudioFormat(format)
                    .setBufferSizeInBytes(bufferSize)
                    .build()
                if (recorder.state == AudioRecord.STATE_INITIALIZED) {
                    return AudioRecordBuild(recorder, source)
                }
                recorder.release()
            } catch (error: Throwable) {
                lastError = error
            }
        }
        lastError?.let { throw it }
        error("AudioRecord failed to initialize")
    }

    private fun shouldRestartForDeviceChange(devices: Array<out AudioDeviceInfo>): Boolean {
        if (!running) return false
        val current = status
        if (current.selectedInputRoute == AudioRoute.AUTO || current.selectedOutputRoute == AudioRoute.AUTO) return true
        val activeInputId = current.activeInputDevice?.id
        val activeOutputId = current.activeOutputDevice?.id
        return devices.any { device ->
            device.id == activeInputId || device.id == activeOutputId ||
                (current.selectedInputRoute == AudioRoute.USB && device.isUsbAudioDevice() && device.isSource) ||
                (current.selectedOutputRoute == AudioRoute.USB && device.isUsbAudioDevice() && device.isSink)
        }
    }

    private fun unavailableRouteMessage(input: AudioDeviceSelection, output: AudioDeviceSelection): String? = when {
        input.device == null && output.device == null -> "Selected input and output audio routes are unavailable"
        input.device == null -> "Selected input audio route is unavailable"
        output.device == null -> "Selected output audio route is unavailable"
        else -> null
    }

    private fun getSelectedInputRoute(): String =
        normalizeInputRoute(BridgeRuntime.getStringPreference(BridgeRuntime.PREF_AUDIO_INPUT_ROUTE, AudioRoute.AUTO))

    private fun getSelectedOutputRoute(): String =
        normalizeOutputRoute(BridgeRuntime.getStringPreference(BridgeRuntime.PREF_AUDIO_OUTPUT_ROUTE, AudioRoute.AUTO))

    private fun normalizeInputRoute(route: String): String = when (route) {
        AudioRoute.AUTO, AudioRoute.USB, AudioRoute.BUILTIN_MIC -> route
        else -> AudioRoute.AUTO
    }

    private fun normalizeOutputRoute(route: String): String = when (route) {
        AudioRoute.AUTO, AudioRoute.USB, AudioRoute.BUILTIN_SPEAKER -> route
        else -> AudioRoute.AUTO
    }

    private fun sourceName(source: Int): String = when (source) {
        MediaRecorder.AudioSource.UNPROCESSED -> "UNPROCESSED"
        MediaRecorder.AudioSource.VOICE_RECOGNITION -> "VOICE_RECOGNITION"
        MediaRecorder.AudioSource.DEFAULT -> "DEFAULT"
        else -> source.toString()
    }

    private fun update(next: UsbAudioStatus) {
        status = next
        listeners.forEach { listener ->
            runCatching { listener(next) }.onFailure { Log.w(TAG, "USB audio listener failed", it) }
        }
        LogBus.i(TAG, "USB audio state=${next.state}, inputs=${next.inputDevices.size}, outputs=${next.outputDevices.size}${next.error?.let { ", error=$it" } ?: ""}")
    }

    private fun elapsedMsSince(startNanos: Long): Double = (System.nanoTime() - startNanos) / 1_000_000.0

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

    private data class AudioFormatChoice(val sampleRate: Int, val bufferSize: Int)

    private data class AudioDeviceSelection(val route: String, val device: AudioDeviceInfo?)

    private data class AudioRecordBuild(val recorder: AudioRecord, val audioSource: Int)

    private data class PcmChunk(
        val bytes: ByteArray,
        val length: Int,
        val createdAtMs: Long,
        val sequence: Long,
        val peak: Int,
    )

    private class LatestPcmQueue(private val capacity: Int) {
        private val lock = Object()
        private val chunks = ArrayDeque<PcmChunk>()
        private var closed = false

        @Volatile var droppedStaleBytes = 0L
            private set
        @Volatile var droppedStaleChunks = 0L
            private set

        fun offer(chunk: PcmChunk) {
            synchronized(lock) {
                if (closed) return
                while (chunks.size >= capacity) {
                    val dropped = chunks.removeFirst()
                    droppedStaleBytes += dropped.length.toLong()
                    droppedStaleChunks += 1
                }
                chunks.addLast(chunk)
                lock.notifyAll()
            }
        }

        fun takeLatest(timeoutMs: Long): PcmChunk? {
            synchronized(lock) {
                if (chunks.isEmpty() && !closed) {
                    try { lock.wait(timeoutMs) } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        return null
                    }
                }
                if (chunks.isEmpty()) return null
                while (chunks.size > 1) {
                    val dropped = chunks.removeFirst()
                    droppedStaleBytes += dropped.length.toLong()
                    droppedStaleChunks += 1
                }
                return chunks.removeLast()
            }
        }

        fun close() {
            synchronized(lock) {
                closed = true
                chunks.clear()
                lock.notifyAll()
            }
        }
    }

    private class InputStats(
        private val sampleRate: Int,
        private val bufferSize: Int,
        private val queue: LatestPcmQueue,
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
        private var windowMaxSocketWriteMs = 0.0
        private var windowMaxChunkAgeMs = 0L

        @Synchronized fun recordCaptured(buffer: ByteArray, length: Int) {
            reads += 1
            bytesRead += length.toLong()
            if (length < bufferSize) shortReads += 1
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
            if (socketWriteMs > windowMaxSocketWriteMs) windowMaxSocketWriteMs = socketWriteMs
            if (chunkAgeMs > windowMaxChunkAgeMs) windowMaxChunkAgeMs = chunkAgeMs
            if (socketWriteMs > SLOW_INPUT_WRITE_MS) writeOverruns += 1
        }

        @Synchronized fun recordReadError() {
            readErrors += 1
        }

        @Synchronized fun logIfDue(message: String) {
            val now = System.currentTimeMillis()
            if (now - lastLoggedAt < STATS_INTERVAL_MS) return
            val elapsedSeconds = ((now - startedAt).coerceAtLeast(1)).toDouble() / 1000.0
            LogBus.i(
                TAG,
                "$message rate=$sampleRate buffer=$bufferSize elapsed=${"%.1f".format(elapsedSeconds)}s bytesRead=$bytesRead bytesWritten=$bytesWritten reads=$reads writes=$writes shortReads=$shortReads readErrors=$readErrors zeroSamples=$zeroSamples/$totalSamples inputDroppedStaleBytes=${queue.droppedStaleBytes} inputDroppedStaleChunks=${queue.droppedStaleChunks} inputWriteOverruns=$writeOverruns inputMaxChunkAgeMs=$windowMaxChunkAgeMs inputSocketWriteMs=${"%.2f".format(windowMaxSocketWriteMs)}"
            )
            lastLoggedAt = now
            windowMaxSocketWriteMs = 0.0
            windowMaxChunkAgeMs = 0L
        }
    }

    private class OutputStats(
        private val sampleRate: Int,
        private val bufferSize: Int,
        private val queue: LatestPcmQueue,
    ) {
        private val startedAt = System.currentTimeMillis()
        private var lastLoggedAt = startedAt
        private var bytesRead = 0L
        private var socketReads = 0L
        private var shortReads = 0L
        private var activeBytesWritten = 0L
        private var silentBytesSkipped = 0L
        private var outputDroppedStaleBytes = 0L
        private var outputDroppedPartialBytes = 0L
        private var flushCount = 0L
        private var partialWrites = 0L
        private var zeroWrites = 0L
        private var writeErrors = 0L
        private var activePeak = 0
        private var windowMaxOutputChunkAgeMs = 0L
        private var windowMaxAudioTrackWriteMs = 0.0

        @Synchronized fun recordSocketRead(readBytes: Int) {
            socketReads += 1
            bytesRead += readBytes.toLong()
            if (readBytes < bufferSize) shortReads += 1
        }

        @Synchronized fun recordChunkAge(chunkAgeMs: Long) {
            if (chunkAgeMs > windowMaxOutputChunkAgeMs) windowMaxOutputChunkAgeMs = chunkAgeMs
        }

        @Synchronized fun recordDroppedStale(length: Int, chunkAgeMs: Long) {
            outputDroppedStaleBytes += length.toLong()
            if (chunkAgeMs > windowMaxOutputChunkAgeMs) windowMaxOutputChunkAgeMs = chunkAgeMs
        }

        @Synchronized fun recordSilence(length: Int) {
            silentBytesSkipped += length.toLong()
        }

        @Synchronized fun recordWrite(writtenBytes: Int, peak: Int, chunkAgeMs: Long, audioTrackWriteMs: Double) {
            activeBytesWritten += writtenBytes.toLong()
            if (peak > activePeak) activePeak = peak
            if (chunkAgeMs > windowMaxOutputChunkAgeMs) windowMaxOutputChunkAgeMs = chunkAgeMs
            if (audioTrackWriteMs > windowMaxAudioTrackWriteMs) windowMaxAudioTrackWriteMs = audioTrackWriteMs
        }

        @Synchronized fun recordPartialWrite(droppedBytes: Int) {
            partialWrites += 1
            outputDroppedPartialBytes += droppedBytes.toLong()
        }

        @Synchronized fun recordZeroWrite(audioTrackWriteMs: Double) {
            zeroWrites += 1
            writeErrors += 1
            if (audioTrackWriteMs > windowMaxAudioTrackWriteMs) windowMaxAudioTrackWriteMs = audioTrackWriteMs
        }

        @Synchronized fun recordFlush() {
            flushCount += 1
        }

        @Synchronized fun logIfDue(message: String) {
            val now = System.currentTimeMillis()
            if (now - lastLoggedAt < STATS_INTERVAL_MS) return
            val elapsedSeconds = ((now - startedAt).coerceAtLeast(1)).toDouble() / 1000.0
            LogBus.i(
                TAG,
                "$message rate=$sampleRate buffer=$bufferSize elapsed=${"%.1f".format(elapsedSeconds)}s bytesRead=$bytesRead activeBytesWritten=$activeBytesWritten socketReads=$socketReads shortReads=$shortReads outputDroppedStaleBytes=${outputDroppedStaleBytes + queue.droppedStaleBytes} outputDroppedStaleChunks=${queue.droppedStaleChunks} outputDroppedPartialBytes=$outputDroppedPartialBytes silentBytesSkipped=$silentBytesSkipped flushCount=$flushCount partialWrites=$partialWrites zeroWrites=$zeroWrites writeErrors=$writeErrors activePeak=$activePeak outputMaxChunkAgeMs=$windowMaxOutputChunkAgeMs maxAudioTrackWriteMs=${"%.2f".format(windowMaxAudioTrackWriteMs)}"
            )
            lastLoggedAt = now
            windowMaxOutputChunkAgeMs = 0L
            windowMaxAudioTrackWriteMs = 0.0
            activePeak = 0
        }
    }
}

data class UsbAudioDevice(
    val id: Int,
    val direction: String,
    val kind: String,
    val type: Int,
    val name: String,
    val sampleRates: List<Int>,
    val channelCounts: List<Int>,
)

data class UsbAudioStatus(
    val state: String = "stopped",
    val selectedInputRoute: String = AudioRoute.AUTO,
    val selectedOutputRoute: String = AudioRoute.AUTO,
    val activeInputDevice: UsbAudioDevice? = null,
    val activeOutputDevice: UsbAudioDevice? = null,
    val inputDevices: List<UsbAudioDevice> = emptyList(),
    val outputDevices: List<UsbAudioDevice> = emptyList(),
    val error: String? = null,
)
