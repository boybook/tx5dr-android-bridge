package com.tx5dr.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidAudioOutputStreamHeaderTest {
    @Test
    fun acceptsS16LeHeader() {
        val result = parseAndroidAudioOutputHeader(header(sampleRate = 44_100, formatId = 1, channels = 1))

        assertTrue(result is AndroidAudioOutputHeaderParseResult.Header)
        val parsed = (result as AndroidAudioOutputHeaderParseResult.Header).header
        assertEquals(44_100, parsed.sampleRate)
        assertEquals(AndroidAudioOutputPcmFormat.S16LE, parsed.format)
        assertEquals(1, parsed.channels)
        assertEquals(2, parsed.format.bytesPerSample)
    }

    @Test
    fun acceptsF32LeHeader() {
        val result = parseAndroidAudioOutputHeader(header(sampleRate = 44_100, formatId = 2, channels = 1))

        assertTrue(result is AndroidAudioOutputHeaderParseResult.Header)
        val parsed = (result as AndroidAudioOutputHeaderParseResult.Header).header
        assertEquals(44_100, parsed.sampleRate)
        assertEquals(AndroidAudioOutputPcmFormat.F32LE, parsed.format)
        assertEquals(4, parsed.format.bytesPerSample)
    }

    @Test
    fun rejectsInvalidRate() {
        val result = parseAndroidAudioOutputHeader(header(sampleRate = 7_999, formatId = 1, channels = 1))

        assertTrue(result is AndroidAudioOutputHeaderParseResult.Invalid)
    }

    @Test
    fun rejectsInvalidFormat() {
        val result = parseAndroidAudioOutputHeader(header(sampleRate = 44_100, formatId = 99, channels = 1))

        assertTrue(result is AndroidAudioOutputHeaderParseResult.Invalid)
    }

    @Test
    fun rejectsNonMonoChannels() {
        val result = parseAndroidAudioOutputHeader(header(sampleRate = 44_100, formatId = 1, channels = 2))

        assertTrue(result is AndroidAudioOutputHeaderParseResult.Invalid)
    }

    @Test
    fun treatsNonHeaderBytesAsLegacyPcm() {
        val result = parseAndroidAudioOutputHeader(ByteArray(ANDROID_AUDIO_OUTPUT_HEADER_BYTES) { it.toByte() })

        assertTrue(result is AndroidAudioOutputHeaderParseResult.Legacy)
    }

    @Test
    fun sizesOutputBuffersByBytesPerSample() {
        assertEquals(4_410, AndroidUsbAudioBridge.bufferSizeFor(44_100, minBuffer = 128, targetMs = 50, bytesPerSample = 2))
        assertEquals(8_820, AndroidUsbAudioBridge.bufferSizeFor(44_100, minBuffer = 128, targetMs = 50, bytesPerSample = 4))
        assertEquals(256, AndroidUsbAudioBridge.bufferSizeFor(44_100, minBuffer = 256, targetMs = 1, bytesPerSample = 2))
    }

    private fun header(sampleRate: Int, formatId: Int, channels: Int): ByteArray {
        val bytes = ByteArray(ANDROID_AUDIO_OUTPUT_HEADER_BYTES)
        "TX5DRAO1".encodeToByteArray().copyInto(bytes, 0)
        bytes[8] = (sampleRate and 0xff).toByte()
        bytes[9] = ((sampleRate shr 8) and 0xff).toByte()
        bytes[10] = ((sampleRate shr 16) and 0xff).toByte()
        bytes[11] = ((sampleRate shr 24) and 0xff).toByte()
        bytes[12] = formatId.toByte()
        bytes[13] = channels.toByte()
        return bytes
    }
}
