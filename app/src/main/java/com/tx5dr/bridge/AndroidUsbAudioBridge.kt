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
import android.os.Process
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.roundToInt


internal const val ANDROID_AUDIO_OUTPUT_HEADER_BYTES = 16
private val ANDROID_AUDIO_OUTPUT_HEADER_MAGIC = byteArrayOf(
    'T'.code.toByte(), 'X'.code.toByte(), '5'.code.toByte(), 'D'.code.toByte(),
    'R'.code.toByte(), 'A'.code.toByte(), 'O'.code.toByte(), '1'.code.toByte(),
)

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
    for (i in ANDROID_AUDIO_OUTPUT_HEADER_MAGIC.indices) {
        if (bytes[i] != ANDROID_AUDIO_OUTPUT_HEADER_MAGIC[i]) return AndroidAudioOutputHeaderParseResult.Legacy
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
    return AndroidAudioOutputHeaderParseResult.Header(AndroidAudioOutputStreamHeader(sampleRate, format, channels))
}

private fun readUInt32Le(bytes: ByteArray, offset: Int): Int {
    val value = (bytes[offset].toLong() and 0xffL) or
        ((bytes[offset + 1].toLong() and 0xffL) shl 8) or
        ((bytes[offset + 2].toLong() and 0xffL) shl 16) or
        ((bytes[offset + 3].toLong() and 0xffL) shl 24)
    return value.toInt()
}

object AudioRoute {
    const val USB = "usb"
    const val BUILTIN_MIC = "builtinMic"
    const val BUILTIN_SPEAKER = "builtinSpeaker"
}

object AndroidUsbAudioBridge {
    private const val TAG = "AudioBridge"
    private const val STATS_INTERVAL_MS = 5000L
    private const val SLOW_INPUT_WRITE_MS = 200.0
    private const val SILENCE_PEAK_THRESHOLD = 4
    private val sampleRates = intArrayOf(48000, 44100)
    private val listeners = CopyOnWriteArrayList<(UsbAudioStatus) -> Unit>()

    @Volatile private var status = UsbAudioStatus()
    @Volatile private var running = false
    private val sessions = mutableMapOf<String, AudioSocketSession>()
    private var devicesFile: File? = null
    @Volatile private var deviceCallbackRegistered = false

    fun init(context: Context, targetFile: File) {
        devicesFile = targetFile
        refreshDevices(context)
    }

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
        val inputs = applyConnectionState("input", uniqueBridgeDevices(manager?.getDevices(AudioManager.GET_DEVICES_INPUTS).orEmpty()
            .filter { it.isSupportedInputDevice() && it.isSource }
            .map { it.toBridgeDevice("input") }))
        val outputs = applyConnectionState("output", uniqueBridgeDevices(manager?.getDevices(AudioManager.GET_DEVICES_OUTPUTS).orEmpty()
            .filter { it.isSupportedOutputDevice() && it.isSink }
            .map { it.toBridgeDevice("output") }))
        val nextState = when {
            hasRecordPermission(context) -> status.state
            status.state == "permission-denied" -> "permission-denied"
            else -> "permission-required"
        }
        update(
            status.copy(
                state = nextState,
                inputDevices = inputs,
                outputDevices = outputs,
                error = null,
            )
        )
        writeDevicesFile(devicesFile ?: BridgeRuntime.paths.androidAudioDevicesFile, inputs, outputs)
        return status
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
        val inputs = currentInputDevices(context)
        val outputs = currentOutputDevices(context)
        if (inputs.isEmpty() || outputs.isEmpty()) {
            update(
                refreshed.copy(
                    state = "no-device",
                    error = "No supported Android audio input or output device",
                )
            )
            return false
        }
        start(context)
        return true
    }

    fun start(context: Context) {
        val app = context.applicationContext
        if (!hasRecordPermission(app)) {
            update(status.copy(state = "permission-required", error = "RECORD_AUDIO permission is required"))
            return
        }
        BridgeService.start(app, BridgeService.ACTION_ENABLE_MICROPHONE_FOREGROUND)
        if (running) return
        val refreshed = refreshDevices(app)
        val inputs = currentInputDevices(app)
        val outputs = currentOutputDevices(app)
        if (inputs.isEmpty() || outputs.isEmpty()) {
            update(
                refreshed.copy(
                    state = "no-device",
                    error = "No supported Android audio input or output device",
                )
            )
            return
        }
        running = true
        update(
            status.copy(
                state = "starting",
                error = null,
            )
        )
        startAudioSessions(app, inputs, outputs)
        update(status.copy(state = "streaming"))
    }

    fun markPermissionDenied() {
        update(status.copy(state = "permission-denied", error = null))
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
        val activeSessions = sessions.values.toList()
        sessions.clear()
        activeSessions.forEach { it.stop() }
        BridgeService.start(BridgeRuntime.appContext(), BridgeService.ACTION_DISABLE_MICROPHONE_FOREGROUND)
        if (stopLinuxSide) BridgeRuntime.stopLinuxAudioSide()
        update(status.copy(state = "stopped"))
    }

    private fun handleDeviceChange(context: Context, reason: String, devices: Array<out AudioDeviceInfo>) {
        val audioChanged = devices.any { it.isSupportedInputDevice() || it.isSupportedOutputDevice() }
        if (!audioChanged) return
        LogBus.i(TAG, "Audio device $reason")
        refreshDevices(context)
        if (running) {
            Thread {
                runCatching {
                    startAudioSessions(context.applicationContext, currentInputDevices(context), currentOutputDevices(context))
                    refreshDevices(context)
                    update(status.copy(state = "streaming"))
                }.onFailure { error ->
                    LogBus.e(TAG, "Audio hotplug reconcile failed", error)
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

    private fun startAudioSessions(context: Context, inputs: List<AudioDeviceInfo>, outputs: List<AudioDeviceInfo>) {
        val desiredKeys = (inputs.map { deviceKey("input", it.id) } + outputs.map { deviceKey("output", it.id) }).toSet()
        sessions.filterKeys { it !in desiredKeys }.values.forEach { it.stop() }
        sessions.keys.removeAll { it !in desiredKeys }
        inputs.forEach { device ->
            val key = deviceKey("input", device.id)
            if (sessions[key]?.isAlive == true) return@forEach
            val bridgeDevice = status.inputDevices.firstOrNull { it.id == device.id } ?: device.toBridgeDevice("input")
            AudioSocketSession(bridgeDevice).also { session ->
                sessions[key] = session
                session.thread = Thread { inputLoop(context, device, bridgeDevice, session) }.also { thread ->
                    thread.name = "tx5dr-audio-input-${device.id}"
                    thread.start()
                }
            }
        }
        outputs.forEach { device ->
            val key = deviceKey("output", device.id)
            if (sessions[key]?.isAlive == true) return@forEach
            val bridgeDevice = status.outputDevices.firstOrNull { it.id == device.id } ?: device.toBridgeDevice("output")
            AudioSocketSession(bridgeDevice).also { session ->
                sessions[key] = session
                session.thread = Thread { outputLoop(context, device, bridgeDevice, session) }.also { thread ->
                    thread.name = "tx5dr-audio-output-${device.id}"
                    thread.start()
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun inputLoop(context: Context, device: AudioDeviceInfo, bridgeDevice: UsbAudioDevice, session: AudioSocketSession) {
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
                            val recordBuild = buildRecorder(format, formatAndBuffer.bufferSize, device)
                            val recorder = recordBuild.recorder
                            if (Build.VERSION.SDK_INT >= 23) recorder.preferredDevice = device
                            if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                                recorder.release()
                                error("AudioRecord failed to initialize")
                            }
                            markDeviceConnected("input", bridgeDevice.id, true)
                            LogBus.i(TAG, "TX-5DR Android input backend connected: ${bridgeDevice.name} source=${sourceName(recordBuild.audioSource)}")
                            val jitterBuffer = PcmJitterBuffer(
                                sampleRate = formatAndBuffer.sampleRate,
                                targetMs = formatAndBuffer.bufferTargetMs,
                                nominalChunkBytes = PcmJitterBuffer.logicalChunkBytes(formatAndBuffer.sampleRate, formatAndBuffer.bufferTargetMs),
                                mode = PcmJitterMode.INPUT,
                            )
                            val connected = AtomicBoolean(true)
                            val captureError = AtomicReference<Throwable?>(null)
                            val stats = InputStats(formatAndBuffer.sampleRate, formatAndBuffer.bufferSize, jitterBuffer)
                            val captureThread = Thread {
                                setAudioThreadPriority("input-capture-${device.id}")
                                val buffer = ByteArray(formatAndBuffer.bufferSize)
                                var sequence = 0L
                                try {
                                    recorder.startRecording()
                                    while (running && session.running && connected.get() && !Thread.currentThread().isInterrupted) {
                                        val beforeRead = System.nanoTime()
                                        val n = recorder.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
                                        val readMs = elapsedMsSince(beforeRead)
                                        if (n > 0) {
                                            stats.recordCaptured(buffer, n, readMs, sequence)
                                            jitterBuffer.offer(PcmChunk(buffer.copyOf(n), n, System.currentTimeMillis(), sequence++, peakAbsPcm16(buffer, n)))
                                        } else {
                                            stats.recordReadError()
                                        }
                                        stats.logIfDue("Android audio input stats")
                                    }
                                } catch (error: Throwable) {
                                    captureError.set(error)
                                } finally {
                                    jitterBuffer.close()
                                }
                            }.also { it.name = "tx5dr-android-audio-input-capture-${device.id}"; it.start() }

                            update(status.copy(state = "streaming"))
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
                                jitterBuffer.close()
                                try { recorder.stop() } catch (_: Throwable) {}
                                recorder.release()
                                try { socket.close() } catch (_: Throwable) {}
                                captureThread.join(1000)
                                markDeviceConnected("input", bridgeDevice.id, false)
                                captureError.get()?.let { throw it }
                                LogBus.i(TAG, "TX-5DR Android input backend disconnected: ${bridgeDevice.name}")
                            }
                        }
                    } catch (error: Throwable) {
                        if (running && session.running) LogBus.w(TAG, "Android audio input client ended for ${bridgeDevice.name}: ${error.message}")
                    }
                }
            }
        } catch (error: Throwable) {
            if (running) {
                LogBus.e(TAG, "USB audio input bridge failed", error)
                update(status.copy(state = "error", error = error.message))
            }
        } finally {
            markDeviceConnected("input", bridgeDevice.id, false)
            session.server = null
        }
    }

    private fun outputLoop(context: Context, device: AudioDeviceInfo, bridgeDevice: UsbAudioDevice, session: AudioSocketSession) {
        setAudioThreadPriority("output-${device.id}")
        try {
            val targetMs = bufferTargetMs()
            val attrs = outputAudioAttributes(bridgeDevice)
            AndroidUnixSocketServer(androidSocketFile(bridgeDevice.socketPath)).use { server ->
                session.server = server
                LogBus.i(TAG, "Audio output available on ${bridgeDevice.socketPath} device=${device.describe()} legacyRate=${choosePlaybackRate(device)} target=${targetMs}ms")
                while (running && session.running) {
                    try {
                        server.accept().use { socket ->
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
                                LogBus.e(
                                    TAG,
                                    "Unsupported Android AudioTrack output contract device=${bridgeDevice.name} rate=${streamHeader.sampleRate} format=${streamHeader.format.label} channels=${streamHeader.channels} minBuffer=$minBuffer",
                                )
                                return@use
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
                            if (Build.VERSION.SDK_INT >= 26) {
                                trackBuilder.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                            }
                            val track = trackBuilder.build()
                            val preferredDeviceApplied = if (Build.VERSION.SDK_INT >= 23) track.setPreferredDevice(device) else false
                            if (track.state != AudioTrack.STATE_INITIALIZED) {
                                track.release()
                                LogBus.e(
                                    TAG,
                                    "AudioTrack failed to initialize for device=${bridgeDevice.name} rate=${streamHeader.sampleRate} format=${streamHeader.format.label} channels=${streamHeader.channels} buffer=$bufferSize",
                                )
                                return@use
                            }
                            markDeviceConnected("output", bridgeDevice.id, true)
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
                            val readerError = AtomicReference<Throwable?>(null)
                            val stats = OutputStats(streamHeader.sampleRate, streamHeader.format.label, streamHeader.format.bytesPerSample, bufferSize, jitterBuffer)
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
                            try {
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
                                        pauseFlush(track)
                                        track.play()
                                        active = true
                                        stats.recordFlush()
                                    }

                                    if (!writePcmFully(track, chunk, streamHeader.format, stats, connected, session, ageMs)) break
                                    stats.logIfDue("Android audio output stats")
                                }
                            } finally {
                                connected.set(false)
                                jitterBuffer.close()
                                pauseFlush(track)
                                track.release()
                                try { socket.close() } catch (_: Throwable) {}
                                readerThread.join(1000)
                                markDeviceConnected("output", bridgeDevice.id, false)
                                readerError.get()?.let { throw it }
                                LogBus.i(TAG, "TX-5DR Android output backend disconnected: ${bridgeDevice.name}")
                            }
                        }
                    } catch (error: Throwable) {
                        if (running && session.running) LogBus.w(TAG, "Android audio output client ended for ${bridgeDevice.name}: ${error.message}")
                    }
                }
            }
        } catch (error: Throwable) {
            if (running) {
                LogBus.e(TAG, "USB audio output bridge failed", error)
                update(status.copy(state = "error", error = error.message))
            }
        } finally {
            markDeviceConnected("output", bridgeDevice.id, false)
            session.server = null
        }
    }

    private fun pauseFlush(track: AudioTrack) {
        try { track.pause() } catch (_: Throwable) {}
        try { track.flush() } catch (_: Throwable) {}
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
                        return false
                    }
                    Thread.sleep(1)
                }
                else -> {
                    stats.recordWriteError()
                    stats.recordDroppedRemainder(chunk.length - offset)
                    LogBus.w(TAG, "AudioTrack int16 write failed with code $written after ${offset}/${chunk.length} bytes")
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
                        return false
                    }
                    Thread.sleep(1)
                }
                else -> {
                    stats.recordWriteError()
                    stats.recordDroppedRemainder((samples.size - sampleOffset) * AndroidAudioOutputPcmFormat.F32LE.bytesPerSample)
                    LogBus.w(TAG, "AudioTrack float32 write failed with code $writtenSamples after ${sampleOffset}/${samples.size} samples")
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
            .filter { it.isSupportedInputDevice() && it.isSource }

    private fun currentOutputDevices(context: Context): List<AudioDeviceInfo> =
        context.applicationContext.getSystemService(AudioManager::class.java)
            ?.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .orEmpty()
            .filter { it.isSupportedOutputDevice() && it.isSink }

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

    private fun outputAudioAttributes(device: UsbAudioDevice): AudioAttributes {
        val (usage, contentType) = if (device.kind == AudioRoute.BUILTIN_SPEAKER) {
            AudioAttributes.USAGE_VOICE_COMMUNICATION to AudioAttributes.CONTENT_TYPE_SPEECH
        } else {
            AudioAttributes.USAGE_MEDIA to AudioAttributes.CONTENT_TYPE_MUSIC
        }
        return AudioAttributes.Builder()
            .setUsage(usage)
            .setContentType(contentType)
            .build()
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
        socketPath = "/opt/tx5dr-data/runtime/sockets/audio-$direction-$id.sock",
    )

    private fun uniqueBridgeDevices(devices: List<UsbAudioDevice>): List<UsbAudioDevice> {
        val counts = mutableMapOf<String, Int>()
        return devices.map { device ->
            val base = "[Android] ${device.name}"
            val count = (counts[base] ?: 0) + 1
            counts[base] = count
            device.copy(name = if (count == 1) base else "$base #$count")
        }
    }

    private fun applyConnectionState(direction: String, devices: List<UsbAudioDevice>): List<UsbAudioDevice> =
        devices.map { device ->
            val connected = sessions[deviceKey(direction, device.id)]?.connected == true
            device.copy(connected = connected)
        }

    private fun deviceKey(direction: String, id: Int): String = "$direction:$id"

    private fun androidSocketFile(prootPath: String): File =
        File(BridgeRuntime.paths.socketsDir, prootPath.substringAfterLast('/'))

    private fun writeDevicesFile(target: File, inputs: List<UsbAudioDevice>, outputs: List<UsbAudioDevice>) {
        val inputDefault = defaultInputDevice(inputs)
        val outputDefault = defaultOutputDevice(outputs)
        fun toJson(device: UsbAudioDevice, isDefault: Boolean) = JSONObject()
            .put("id", "android-${device.direction}-${device.id}")
            .put("androidDeviceId", device.id)
            .put("name", device.name)
            .put("direction", device.direction)
            .put("kind", device.kind)
            .put("type", device.type)
            .put("channels", 1)
            .put("sampleRate", preferredSampleRate(device.sampleRates))
            .put("sampleRates", JSONArray(device.sampleRates.ifEmpty { sampleRates.toList() }))
            .put("format", "s16le")
            .put("formats", JSONArray(listOf("s16le", "f32le")))
            .put("socketPath", device.socketPath)
            .put("available", true)
            .put("isDefault", isDefault)
        val root = JSONObject()
            .put("updatedAt", System.currentTimeMillis())
            .put("socketDir", "/opt/tx5dr-data/runtime/sockets")
            .put("format", "s16le")
            .put("formats", JSONArray(listOf("s16le", "f32le")))
            .put("channels", 1)
            .put("bufferTargetMs", bufferTargetMs())
        val inputArray = JSONArray()
        inputs.forEach { inputArray.put(toJson(it, it.id == inputDefault?.id)) }
        val outputArray = JSONArray()
        outputs.forEach { outputArray.put(toJson(it, it.id == outputDefault?.id)) }
        root.put("inputDevices", inputArray)
        root.put("outputDevices", outputArray)
        runCatching {
            target.parentFile?.mkdirs()
            target.writeText(root.toString(2))
        }.onFailure { error -> LogBus.w(TAG, "Failed to write audio devices file: ${error.message}") }
    }

    private fun defaultInputDevice(inputs: List<UsbAudioDevice>): UsbAudioDevice? =
        inputs.firstOrNull { it.kind == AudioRoute.USB } ?: inputs.firstOrNull { it.kind == AudioRoute.BUILTIN_MIC } ?: inputs.firstOrNull()

    private fun defaultOutputDevice(outputs: List<UsbAudioDevice>): UsbAudioDevice? =
        outputs.firstOrNull { it.kind == AudioRoute.USB } ?: outputs.firstOrNull { it.kind == AudioRoute.BUILTIN_SPEAKER } ?: outputs.firstOrNull()

    private fun preferredSampleRate(rates: List<Int>): Int =
        sampleRates.firstOrNull { it in rates }.let { it ?: rates.firstOrNull() ?: 48000 }

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

    private fun markDeviceConnected(direction: String, id: Int, connected: Boolean) {
        sessions[deviceKey(direction, id)]?.connected = connected
        val current = status
        val next = when (direction) {
            "input" -> current.copy(inputDevices = current.inputDevices.map { if (it.id == id) it.copy(connected = connected) else it })
            "output" -> current.copy(outputDevices = current.outputDevices.map { if (it.id == id) it.copy(connected = connected) else it })
            else -> current
        }
        update(next)
    }

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

    private class AudioSocketSession(val device: UsbAudioDevice) {
        @Volatile var running = true
        @Volatile var server: AndroidUnixSocketServer? = null
        @Volatile var thread: Thread? = null
        @Volatile var connected: Boolean = false
        val isAlive: Boolean get() = running && thread?.isAlive == true

        fun stop() {
            running = false
            connected = false
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

data class UsbAudioDevice(
    val id: Int,
    val direction: String,
    val kind: String,
    val type: Int,
    val name: String,
    val sampleRates: List<Int>,
    val channelCounts: List<Int>,
    val socketPath: String,
    val connected: Boolean = false,
)

data class UsbAudioStatus(
    val state: String = "stopped",
    val inputDevices: List<UsbAudioDevice> = emptyList(),
    val outputDevices: List<UsbAudioDevice> = emptyList(),
    val error: String? = null,
)
