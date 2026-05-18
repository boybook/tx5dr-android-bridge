package com.tx5dr.bridge

import android.os.Handler
import android.os.Looper
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

object LogBus {
    private const val MAX_LINES = 250
    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<(String) -> Unit>()
    private val lines = ArrayDeque<String>()
    private val formatter = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    @Synchronized
    fun snapshot(): String = lines.joinToString("\n")

    fun addListener(listener: (String) -> Unit) {
        listeners.add(listener)
        runCatching { listener(snapshot()) }.onFailure { Log.w("LogBus", "Log listener failed", it) }
    }

    fun removeListener(listener: (String) -> Unit) {
        listeners.remove(listener)
    }

    fun i(tag: String, message: String) = append("INFO", tag, message, null)
    fun w(tag: String, message: String) = append("WARN", tag, message, null)
    fun e(tag: String, message: String, error: Throwable? = null) = append("ERROR", tag, message, error)

    private fun append(level: String, tag: String, message: String, error: Throwable?) {
        val line = "${formatter.format(Date())} [$level] [$tag] $message" + (error?.let { " (${it.message})" } ?: "")
        when (level) {
            "ERROR" -> Log.e(tag, message, error)
            "WARN" -> Log.w(tag, message, error)
            else -> Log.i(tag, message)
        }
        val text = synchronized(this) {
            lines.addLast(line)
            while (lines.size > MAX_LINES) lines.removeFirst()
            lines.joinToString("\n")
        }
        main.post {
            listeners.forEach { listener ->
                runCatching { listener(text) }.onFailure { Log.w("LogBus", "Log listener failed", it) }
            }
        }
    }
}
