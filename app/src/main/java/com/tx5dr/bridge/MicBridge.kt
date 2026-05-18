package com.tx5dr.bridge

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList

object MicBridge {
    private const val TAG = "AudioBridge"
    private const val PORT = 4719
    private const val SAMPLE_RATE = 44100
    private val listeners = CopyOnWriteArrayList<(MicBridgeState) -> Unit>()
    @Volatile private var state = MicBridgeState.Stopped
    @Volatile private var running = false
    private var thread: Thread? = null
    private var serverSocket: ServerSocket? = null

    fun addListener(listener: (MicBridgeState) -> Unit) {
        listeners.add(listener)
        listener(state)
    }

    fun removeListener(listener: (MicBridgeState) -> Unit) {
        listeners.remove(listener)
    }

    fun hasPermission(context: Context): Boolean = if (Build.VERSION.SDK_INT >= 23) {
        context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    } else true

    fun start(context: Context) {
        if (!hasPermission(context)) {
            setState(MicBridgeState.PermissionRequired)
            return
        }
        if (running) return
        running = true
        setState(MicBridgeState.Starting)
        thread = Thread { streamLoop(context.applicationContext) }.also { it.name = "tx5dr-mic-bridge"; it.start() }
        BridgeRuntime.startLinuxMicSide()
    }

    fun stop() {
        running = false
        try { serverSocket?.close() } catch (_: Throwable) {}
        BridgeRuntime.stopLinuxMicSide()
        thread?.join(1500)
        thread = null
        setState(MicBridgeState.Stopped)
    }

    private fun streamLoop(context: Context) {
        var recorder: AudioRecord? = null
        try {
            val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val bufferSize = maxOf(minBuffer, SAMPLE_RATE / 5 * 2)
            @Suppress("DEPRECATION")
            recorder = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize,
            )
            require(recorder.state == AudioRecord.STATE_INITIALIZED) { "AudioRecord failed to initialize" }
            ServerSocket(PORT, 1, InetAddress.getByName("127.0.0.1")).use { server ->
                serverSocket = server
                LogBus.i(TAG, "Mic bridge waiting on 127.0.0.1:$PORT")
                server.accept().use { socket ->
                    LogBus.i(TAG, "Linux mic injector connected")
                    recorder.startRecording()
                    setState(MicBridgeState.Streaming)
                    val buffer = ByteArray(bufferSize)
                    while (running && !socket.isClosed) {
                        val n = recorder.read(buffer, 0, buffer.size)
                        if (n > 0) socket.getOutputStream().write(buffer, 0, n)
                    }
                }
            }
        } catch (error: Throwable) {
            if (running) {
                LogBus.e(TAG, "Mic bridge failed", error)
                setState(MicBridgeState.Error)
            }
        } finally {
            try { recorder?.stop() } catch (_: Throwable) {}
            recorder?.release()
            try { serverSocket?.close() } catch (_: Throwable) {}
            serverSocket = null
            if (!running && state != MicBridgeState.Stopped) setState(MicBridgeState.Stopped)
        }
    }

    private fun setState(next: MicBridgeState) {
        state = next
        listeners.forEach { it(next) }
        LogBus.i(TAG, "state=$next")
    }
}
