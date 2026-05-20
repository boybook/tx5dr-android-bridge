package com.tx5dr.bridge

import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

internal data class PcmChunk(
    val bytes: ByteArray,
    val length: Int,
    val createdAtMs: Long,
    val sequence: Long,
    val peak: Int,
)

internal enum class PcmJitterMode {
    INPUT,
    OUTPUT,
}

internal enum class PcmTakeState {
    CHUNK,
    WAITING,
    UNDERRUN,
    CLOSED,
}

internal data class PcmTakeResult(
    val state: PcmTakeState,
    val chunk: PcmChunk? = null,
    val snapshot: PcmJitterSnapshot,
)

internal data class PcmJitterWatermarks(
    val targetMs: Int,
    val effectiveTargetMs: Int,
    val lowWaterMs: Double,
    val startWaterMs: Double,
    val resumeWaterMs: Double,
    val highWaterMs: Double,
    val hardMaxMs: Double,
    val dropToMs: Double,
)

internal data class PcmJitterSnapshot(
    val watermarks: PcmJitterWatermarks,
    val bufferedMs: Double,
    val chunks: Int,
    val started: Boolean,
    val rebuffering: Boolean,
    val startWaitCount: Long,
    val rebufferCount: Long,
    val underrunCount: Long,
    val overflowDropBytes: Long,
    val overflowDropChunks: Long,
    val fastForwardCount: Long,
    val bufferedLatency: LatencySummary = LatencySummary(),
    val consumerWaitLatency: LatencySummary = LatencySummary(),
)

internal class PcmJitterBuffer(
    sampleRate: Int,
    targetMs: Int,
    nominalChunkBytes: Int,
    private val mode: PcmJitterMode,
    private val bytesPerSample: Int = 2,
) {
    private val lock = Object()
    private val chunks = ArrayDeque<PcmChunk>()
    private val bytesPerSecond = max(1, sampleRate * bytesPerSample)
    private val watermarks = buildWatermarks(sampleRate, targetMs, nominalChunkBytes, bytesPerSample, mode)
    private val bufferedLatency = LatencyWindow()
    private val consumerWaitLatency = LatencyWindow()
    private var bufferedBytes = 0L
    private var closed = false
    private var started = false
    private var rebuffering = false
    private var startWaitCount = 0L
    private var rebufferCount = 0L
    private var underrunCount = 0L
    private var overflowDropBytes = 0L
    private var overflowDropChunks = 0L
    private var fastForwardCount = 0L

    fun offer(chunk: PcmChunk) {
        synchronized(lock) {
            if (closed) return
            chunks.addLast(chunk)
            bufferedBytes += chunk.length.toLong()
            trimOverflowLocked()
            recordBufferedLocked()
            lock.notifyAll()
        }
    }

    fun waitForProducerRoom(timeoutMs: Long): Boolean {
        synchronized(lock) {
            if (closed) return false
            if (bufferedMsLocked() < watermarks.highWaterMs) return true
            waitLocked(timeoutMs, System.nanoTime())
            return !closed && bufferedMsLocked() < watermarks.highWaterMs
        }
    }

    fun take(timeoutMs: Long): PcmTakeResult {
        synchronized(lock) {
            val waitStartedAt = System.nanoTime()
            while (!closed) {
                val bufferedMs = bufferedMsLocked()
                if (!started) {
                    val waterMs = if (rebuffering) watermarks.resumeWaterMs else watermarks.startWaterMs
                    if (chunks.isNotEmpty() && bufferedMs >= waterMs) {
                        started = true
                        rebuffering = false
                        return chunkResultLocked()
                    }
                    startWaitCount += 1
                    waitLocked(timeoutMs, waitStartedAt)
                    val afterWaitMs = bufferedMsLocked()
                    if (!started && (chunks.isEmpty() || afterWaitMs < waterMs)) {
                        return resultLocked(PcmTakeState.WAITING)
                    }
                    continue
                }

                if (chunks.isNotEmpty()) {
                    return chunkResultLocked()
                }

                waitLocked(timeoutMs, waitStartedAt)
                if (chunks.isNotEmpty()) {
                    return chunkResultLocked()
                }
                if (closed) break
                started = false
                rebuffering = true
                rebufferCount += 1
                underrunCount += 1
                recordBufferedLocked()
                return resultLocked(PcmTakeState.UNDERRUN)
            }
            return resultLocked(PcmTakeState.CLOSED)
        }
    }

    fun close() {
        synchronized(lock) {
            closed = true
            chunks.clear()
            bufferedBytes = 0
            lock.notifyAll()
        }
    }

    fun snapshot(resetWindows: Boolean = true): PcmJitterSnapshot {
        synchronized(lock) {
            return snapshotLocked(resetWindows)
        }
    }

    private fun chunkResultLocked(): PcmTakeResult {
        val chunk = chunks.removeFirst()
        bufferedBytes -= chunk.length.toLong()
        if (bufferedBytes < 0) bufferedBytes = 0
        recordBufferedLocked()
        lock.notifyAll()
        return resultLocked(PcmTakeState.CHUNK, chunk)
    }

    private fun trimOverflowLocked() {
        if (bufferedMsLocked() <= watermarks.hardMaxMs) return
        var droppedBytes = 0L
        var droppedChunks = 0L
        while (chunks.size > 1 && bufferedMsLocked() > watermarks.dropToMs) {
            val dropped = chunks.removeFirst()
            bufferedBytes -= dropped.length.toLong()
            droppedBytes += dropped.length.toLong()
            droppedChunks += 1
        }
        if (droppedBytes > 0) {
            overflowDropBytes += droppedBytes
            overflowDropChunks += droppedChunks
            fastForwardCount += 1
            if (!started) {
                // After a fast-forward during prebuffering, require the normal target again.
                rebuffering = false
            }
        }
    }

    private fun resultLocked(state: PcmTakeState, chunk: PcmChunk? = null): PcmTakeResult =
        PcmTakeResult(state = state, chunk = chunk, snapshot = snapshotLocked(resetWindows = false))

    private fun snapshotLocked(resetWindows: Boolean): PcmJitterSnapshot = PcmJitterSnapshot(
        watermarks = watermarks,
        bufferedMs = bufferedMsLocked(),
        chunks = chunks.size,
        started = started,
        rebuffering = rebuffering,
        startWaitCount = startWaitCount,
        rebufferCount = rebufferCount,
        underrunCount = underrunCount,
        overflowDropBytes = overflowDropBytes,
        overflowDropChunks = overflowDropChunks,
        fastForwardCount = fastForwardCount,
        bufferedLatency = bufferedLatency.snapshot(resetWindows),
        consumerWaitLatency = consumerWaitLatency.snapshot(resetWindows),
    )

    private fun bufferedMsLocked(): Double = bufferedBytes.toDouble() * 1000.0 / bytesPerSecond.toDouble()

    private fun recordBufferedLocked() {
        bufferedLatency.record(bufferedMsLocked())
    }

    private fun waitLocked(timeoutMs: Long, waitStartedAt: Long) {
        val boundedTimeout = max(1L, timeoutMs)
        try {
            lock.wait(boundedTimeout)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } finally {
            consumerWaitLatency.record((System.nanoTime() - waitStartedAt) / 1_000_000.0)
        }
    }

    companion object {
        fun buildWatermarks(
            sampleRate: Int,
            targetMs: Int,
            nominalChunkBytes: Int,
            bytesPerSample: Int = 2,
            mode: PcmJitterMode = PcmJitterMode.OUTPUT,
        ): PcmJitterWatermarks {
            val bytesPerSecond = max(1, sampleRate * bytesPerSample)
            val chunkMs = max(1.0, nominalChunkBytes.toDouble() * 1000.0 / bytesPerSecond.toDouble())
            val sanitizedTargetMs = targetMs.coerceAtLeast(1)
            val effectiveTargetMs = max(sanitizedTargetMs, ceil(chunkMs * 2.0).toInt())
            val lowWaterMs = max(chunkMs, effectiveTargetMs * 0.5)
            val highWaterMs = if (mode == PcmJitterMode.INPUT) {
                max(effectiveTargetMs + chunkMs, effectiveTargetMs * 1.25)
            } else {
                max(effectiveTargetMs + chunkMs, effectiveTargetMs * 2.0)
            }
            val hardMaxMs = if (mode == PcmJitterMode.INPUT) {
                max(highWaterMs + chunkMs, effectiveTargetMs * 1.75)
            } else {
                max(max(highWaterMs + chunkMs * 2.0, effectiveTargetMs * 4.0), effectiveTargetMs + 180.0)
            }
            val dropToMs = if (mode == PcmJitterMode.INPUT) effectiveTargetMs.toDouble() else highWaterMs
            return PcmJitterWatermarks(
                targetMs = sanitizedTargetMs,
                effectiveTargetMs = effectiveTargetMs,
                lowWaterMs = lowWaterMs,
                startWaterMs = effectiveTargetMs.toDouble(),
                resumeWaterMs = effectiveTargetMs.toDouble(),
                highWaterMs = highWaterMs,
                hardMaxMs = hardMaxMs,
                dropToMs = dropToMs,
            )
        }

        fun logicalChunkBytes(sampleRate: Int, targetMs: Int, bytesPerSample: Int = 2): Int {
            val logicalChunkMs = min(20.0, max(5.0, targetMs.coerceAtLeast(1).toDouble() / 2.0))
            val bytes = (sampleRate.toDouble() * logicalChunkMs / 1000.0 * bytesPerSample).roundToLong().toInt()
            return max(bytesPerSample, bytes - (bytes % bytesPerSample))
        }
    }
}
