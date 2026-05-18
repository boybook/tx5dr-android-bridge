package com.tx5dr.bridge

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Build
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList

object AndroidUsbAudioBridge {
    private const val TAG = "AudioBridge"
    private const val INPUT_PORT = 4719
    private const val OUTPUT_PORT = 4720
    private val sampleRates = intArrayOf(48000, 44100)
    private val listeners = CopyOnWriteArrayList<(UsbAudioStatus) -> Unit>()

    @Volatile private var status = UsbAudioStatus()
    @Volatile private var running = false
    private var inputThread: Thread? = null
    private var outputThread: Thread? = null
    private var inputServer: ServerSocket? = null
    private var outputServer: ServerSocket? = null

    fun addListener(listener: (UsbAudioStatus) -> Unit) {
        listeners.add(listener)
        listener(status)
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
            .filter { it.isUsbAudioDevice() && it.isSource }
            .map { it.toBridgeDevice("input") }
        val outputs = manager?.getDevices(AudioManager.GET_DEVICES_OUTPUTS).orEmpty()
            .filter { it.isUsbAudioDevice() && it.isSink }
            .map { it.toBridgeDevice("output") }
        update(status.copy(inputDevices = inputs, outputDevices = outputs, error = null))
        return status
    }

    fun start(context: Context) {
        val app = context.applicationContext
        if (!hasRecordPermission(app)) {
            update(status.copy(state = "permission-required", error = "RECORD_AUDIO permission is required"))
            return
        }
        if (running) return
        refreshDevices(app)
        running = true
        update(status.copy(state = "starting", error = null))
        inputThread = Thread { inputLoop(app) }.also { it.name = "tx5dr-usb-audio-input"; it.start() }
        outputThread = Thread { outputLoop(app) }.also { it.name = "tx5dr-usb-audio-output"; it.start() }
        BridgeRuntime.startLinuxAudioSide()
    }

    fun stop() {
        running = false
        try { inputServer?.close() } catch (_: Throwable) {}
        try { outputServer?.close() } catch (_: Throwable) {}
        BridgeRuntime.stopLinuxAudioSide()
        inputThread?.join(1500)
        outputThread?.join(1500)
        inputThread = null
        outputThread = null
        update(status.copy(state = "stopped"))
    }

    @SuppressLint("MissingPermission")
    private fun inputLoop(context: Context) {
        var recorder: AudioRecord? = null
        try {
            val device = selectInputDevice(context)
            val formatAndBuffer = chooseRecordFormat(device)
            val format = AudioFormat.Builder()
                .setSampleRate(formatAndBuffer.sampleRate)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                .build()
            recorder = AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.DEFAULT)
                .setAudioFormat(format)
                .setBufferSizeInBytes(formatAndBuffer.bufferSize)
                .build()
            if (device != null && Build.VERSION.SDK_INT >= 23) recorder.preferredDevice = device
            require(recorder.state == AudioRecord.STATE_INITIALIZED) { "AudioRecord failed to initialize" }
            ServerSocket(INPUT_PORT, 1, InetAddress.getByName("127.0.0.1")).use { server ->
                inputServer = server
                LogBus.i(TAG, "USB audio input waiting on 127.0.0.1:$INPUT_PORT device=${device?.productName ?: "default"} rate=${formatAndBuffer.sampleRate} buffer=${formatAndBuffer.bufferSize}")
                server.accept().use { socket ->
                    LogBus.i(TAG, "Linux Pulse input injector connected")
                    recorder.startRecording()
                    update(status.copy(state = "streaming"))
                    val buffer = ByteArray(formatAndBuffer.bufferSize)
                    val out = socket.getOutputStream()
                    while (running && !socket.isClosed) {
                        val n = recorder.read(buffer, 0, buffer.size)
                        if (n > 0) out.write(buffer, 0, n)
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

    private fun outputLoop(context: Context) {
        var track: AudioTrack? = null
        try {
            val device = selectOutputDevice(context)
            val rate = choosePlaybackRate(device)
            val minBuffer = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val bufferSize = maxOf(minBuffer, rate / 5 * 2)
            val format = AudioFormat.Builder()
                .setSampleRate(rate)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build()
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
            track = AudioTrack.Builder()
                .setAudioAttributes(attrs)
                .setAudioFormat(format)
                .setBufferSizeInBytes(bufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            if (device != null && Build.VERSION.SDK_INT >= 23) track.preferredDevice = device
            require(track.state == AudioTrack.STATE_INITIALIZED) { "AudioTrack failed to initialize" }
            ServerSocket(OUTPUT_PORT, 1, InetAddress.getByName("127.0.0.1")).use { server ->
                outputServer = server
                LogBus.i(TAG, "USB audio output waiting on 127.0.0.1:$OUTPUT_PORT device=${device?.productName ?: "default"} rate=$rate buffer=$bufferSize")
                server.accept().use { socket ->
                    LogBus.i(TAG, "Linux Pulse output capture connected")
                    track.play()
                    val buffer = ByteArray(bufferSize)
                    val input = socket.getInputStream()
                    while (running && !socket.isClosed) {
                        val n = input.read(buffer)
                        if (n <= 0) break
                        track.write(buffer, 0, n)
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

    private fun selectInputDevice(context: Context): AudioDeviceInfo? = context.applicationContext
        .getSystemService(AudioManager::class.java)
        ?.getDevices(AudioManager.GET_DEVICES_INPUTS)
        ?.firstOrNull { it.isUsbAudioDevice() && it.isSource }

    private fun selectOutputDevice(context: Context): AudioDeviceInfo? = context.applicationContext
        .getSystemService(AudioManager::class.java)
        ?.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        ?.firstOrNull { it.isUsbAudioDevice() && it.isSink }

    private fun chooseRecordFormat(device: AudioDeviceInfo?): AudioFormatChoice {
        val rates = device?.sampleRates?.takeIf { it.isNotEmpty() } ?: sampleRates
        for (rate in sampleRates.filter { it in rates }.ifEmpty { rates.toList() }) {
            val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            if (min > 0) return AudioFormatChoice(rate, maxOf(min, rate / 5 * 2))
        }
        error("No supported USB input format")
    }

    private fun choosePlaybackRate(device: AudioDeviceInfo?): Int {
        val rates = device?.sampleRates?.takeIf { it.isNotEmpty() } ?: sampleRates
        return sampleRates.firstOrNull { it in rates } ?: rates.first()
    }

    private fun AudioDeviceInfo.isUsbAudioDevice(): Boolean = type == AudioDeviceInfo.TYPE_USB_DEVICE || type == AudioDeviceInfo.TYPE_USB_HEADSET

    private fun AudioDeviceInfo.toBridgeDevice(direction: String): UsbAudioDevice = UsbAudioDevice(
        id = id,
        direction = direction,
        name = productName?.toString()?.takeIf { it.isNotBlank() } ?: "USB Audio $id",
        sampleRates = sampleRates.toList(),
        channelCounts = channelCounts.toList(),
    )

    private fun update(next: UsbAudioStatus) {
        status = next
        listeners.forEach { it(next) }
        LogBus.i(TAG, "USB audio state=${next.state}, inputs=${next.inputDevices.size}, outputs=${next.outputDevices.size}${next.error?.let { ", error=$it" } ?: ""}")
    }

    private data class AudioFormatChoice(val sampleRate: Int, val bufferSize: Int)
}

data class UsbAudioDevice(
    val id: Int,
    val direction: String,
    val name: String,
    val sampleRates: List<Int>,
    val channelCounts: List<Int>,
)

data class UsbAudioStatus(
    val state: String = "stopped",
    val inputDevices: List<UsbAudioDevice> = emptyList(),
    val outputDevices: List<UsbAudioDevice> = emptyList(),
    val error: String? = null,
)
