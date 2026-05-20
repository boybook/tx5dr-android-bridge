package com.tx5dr.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PcmJitterBufferTest {
    @Test
    fun waitsUntilStartWatermarkBeforeConsuming() {
        val buffer = PcmJitterBuffer(sampleRate = 1_000, targetMs = 40, nominalChunkBytes = 40, mode = PcmJitterMode.OUTPUT)
        buffer.offer(chunk(length = 40, sequence = 1))

        val waiting = buffer.take(1)
        assertEquals(PcmTakeState.WAITING, waiting.state)

        buffer.offer(chunk(length = 40, sequence = 2))
        val first = buffer.take(1)
        assertEquals(PcmTakeState.CHUNK, first.state)
        assertEquals(1L, first.chunk?.sequence)
    }

    @Test
    fun keepsFifoOrderAfterStart() {
        val buffer = PcmJitterBuffer(sampleRate = 1_000, targetMs = 40, nominalChunkBytes = 40, mode = PcmJitterMode.OUTPUT)
        buffer.offer(chunk(length = 40, sequence = 1))
        buffer.offer(chunk(length = 40, sequence = 2))
        buffer.offer(chunk(length = 40, sequence = 3))

        assertEquals(1L, buffer.take(1).chunk?.sequence)
        assertEquals(2L, buffer.take(1).chunk?.sequence)
        assertEquals(3L, buffer.take(1).chunk?.sequence)
    }

    @Test
    fun doesNotDropBeforeHardMaximum() {
        val buffer = PcmJitterBuffer(sampleRate = 1_000, targetMs = 40, nominalChunkBytes = 40, mode = PcmJitterMode.OUTPUT)
        for (sequence in 1L..5L) {
            buffer.offer(chunk(length = 40, sequence = sequence))
        }

        val snapshot = buffer.snapshot(resetWindows = false)
        assertEquals(0, snapshot.overflowDropBytes)
        assertEquals(100.0, snapshot.bufferedMs, 0.01)
    }

    @Test
    fun fastForwardsOldChunksAfterHardMaximum() {
        val buffer = PcmJitterBuffer(sampleRate = 1_000, targetMs = 40, nominalChunkBytes = 40, mode = PcmJitterMode.OUTPUT)
        for (sequence in 1L..12L) {
            buffer.offer(chunk(length = 40, sequence = sequence))
        }

        val snapshot = buffer.snapshot(resetWindows = false)
        assertTrue(snapshot.overflowDropBytes > 0)
        assertEquals(1, snapshot.fastForwardCount)
        assertTrue(snapshot.bufferedMs <= snapshot.watermarks.dropToMs)
        assertEquals(9L, buffer.take(1).chunk?.sequence)
    }

    @Test
    fun entersRebufferAfterUnderrunAndWaitsForResumeWatermark() {
        val buffer = PcmJitterBuffer(sampleRate = 1_000, targetMs = 40, nominalChunkBytes = 40, mode = PcmJitterMode.OUTPUT)
        buffer.offer(chunk(length = 40, sequence = 1))
        buffer.offer(chunk(length = 40, sequence = 2))
        assertNotNull(buffer.take(1).chunk)
        assertNotNull(buffer.take(1).chunk)

        assertEquals(PcmTakeState.UNDERRUN, buffer.take(1).state)
        buffer.offer(chunk(length = 40, sequence = 3))
        assertEquals(PcmTakeState.WAITING, buffer.take(1).state)
        buffer.offer(chunk(length = 40, sequence = 4))
        assertEquals(3L, buffer.take(1).chunk?.sequence)
    }

    @Test
    fun raisesEffectiveTargetWhenNominalChunkIsLarge() {
        val watermarks = PcmJitterBuffer.buildWatermarks(
            sampleRate = 1_000,
            targetMs = 10,
            nominalChunkBytes = 60,
            mode = PcmJitterMode.OUTPUT,
        )

        assertEquals(60, watermarks.effectiveTargetMs)
        assertEquals(60.0, watermarks.startWaterMs, 0.01)
    }

    private fun chunk(length: Int, sequence: Long): PcmChunk = PcmChunk(
        bytes = ByteArray(length),
        length = length,
        createdAtMs = System.currentTimeMillis(),
        sequence = sequence,
        peak = 100,
    )
}
