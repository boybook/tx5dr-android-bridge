package com.tx5dr.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidAudioDevicePolicyTest {
    @Test
    fun `stale audio session generation cannot update a replacement session`() {
        assertTrue(isCurrentAudioSessionGeneration(expected = 8, current = 8))
        assertFalse(isCurrentAudioSessionGeneration(expected = 8, current = 9))
        assertFalse(isCurrentAudioSessionGeneration(expected = 8, current = null))
    }

    @Test
    fun `classifies wired headset independently by source and sink capability`() {
        val input = classify(AndroidAudioDeviceType.WIRED_HEADSET, "input", isSource = true, isSink = true)
        val output = classify(AndroidAudioDeviceType.WIRED_HEADSET, "output", isSource = true, isSink = true)
        assertEquals(AudioRoute.WIRED_HEADSET, input?.kind)
        assertEquals("analog", input?.transport)
        assertEquals("3.5mm", input?.connector)
        assertEquals(AudioRoute.WIRED_HEADSET, output?.kind)
        assertNull(classify(AndroidAudioDeviceType.WIRED_HEADSET, "input", isSource = false, isSink = true))
    }

    @Test
    fun `wired headphones never become an input even if HAL marks source`() {
        assertNull(classify(AndroidAudioDeviceType.WIRED_HEADPHONES, "input", isSource = true, isSink = true))
        assertEquals(AudioRoute.WIRED_HEADPHONES, classify(AndroidAudioDeviceType.WIRED_HEADPHONES, "output", true, true)?.kind)
    }

    @Test
    fun `line and aux analog endpoints honor source and sink flags`() {
        assertEquals(AudioRoute.ANALOG_LINE, classify(AndroidAudioDeviceType.LINE_ANALOG, "input", true, false)?.kind)
        assertNull(classify(AndroidAudioDeviceType.LINE_ANALOG, "output", true, false))
        assertEquals(AudioRoute.ANALOG_LINE, classify(AndroidAudioDeviceType.AUX_LINE, "output", false, true)?.kind)
    }

    @Test
    fun `wired identity survives Android device id changes`() {
        val first = classify(AndroidAudioDeviceType.WIRED_HEADSET, "input", true, true, id = 7)!!
        val replugged = classify(AndroidAudioDeviceType.WIRED_HEADSET, "input", true, true, id = 41)!!
        assertEquals("android:wired-headset:input", first.routeKey)
        assertEquals(first.routeKey, replugged.routeKey)
        assertEquals("android-input-wired-headset", first.manifestId)
        assertEquals(first.manifestId, replugged.manifestId)
        assertEquals(first.socketPath, replugged.socketPath)
        assertTrue(first.socketPath.substringAfterLast('/').matches(Regex("ai-[0-9a-f]{16}\\.sock")))
    }

    @Test
    fun `USB identity keeps legacy manifest id and socket path`() {
        val usb = classify(AndroidAudioDeviceType.USB_HEADSET, "output", true, true, id = 17)!!
        assertEquals("android-output-17", usb.manifestId)
        assertEquals("/opt/tx5dr-data/runtime/sockets/audio-output-17.sock", usb.socketPath)
    }

    @Test
    fun `USB route key remains stable while legacy endpoint id follows Android id`() {
        val first = classify(AndroidAudioDeviceType.USB_HEADSET, "output", true, true, id = 17, address = "card=2;device=0")!!
        val replugged = classify(AndroidAudioDeviceType.USB_HEADSET, "output", true, true, id = 48, address = "card=2;device=0")!!
        assertEquals("android:usb:output:card-2-device-0", first.routeKey)
        assertEquals(first.routeKey, replugged.routeKey)
        assertEquals("android-output-17", first.manifestId)
        assertEquals("android-output-48", replugged.manifestId)
    }

    @Test
    fun `duplicate logical routes use stable address suffixes`() {
        val a = classify(AndroidAudioDeviceType.AUX_LINE, "output", false, true, id = 1, address = "rear/line A")!!
        val b = classify(AndroidAudioDeviceType.AUX_LINE, "output", false, true, id = 2, address = "rear/line B")!!
        val unique = AndroidAudioDevicePolicy.makeIdentitiesUnique(listOf(a, b))
        assertEquals("android:analog-line:output:rear-line-a", unique[0].routeKey)
        assertEquals("android-output-analog-line-rear-line-a", unique[0].manifestId)
        assertTrue(unique[1].socketPath.substringAfterLast('/').matches(Regex("ao-[0-9a-f]{16}\\.sock")))
        assertFalse(unique[0].socketPath == unique[1].socketPath)
    }

    @Test
    fun `address identity remains stable as peer endpoints appear and disappear`() {
        val a = classify(AndroidAudioDeviceType.AUX_LINE, "output", false, true, id = 1, address = "rear/a")!!
        val b = classify(AndroidAudioDeviceType.AUX_LINE, "output", false, true, id = 2, address = "rear/b")!!
        val aloneBefore = AndroidAudioDevicePolicy.makeIdentitiesUnique(listOf(a)).single()
        val together = AndroidAudioDevicePolicy.makeIdentitiesUnique(listOf(a, b))
        val aloneAfter = AndroidAudioDevicePolicy.makeIdentitiesUnique(listOf(a)).single()
        assertEquals(aloneBefore.routeKey, together.first { it.id == a.id }.routeKey)
        assertEquals(aloneBefore.routeKey, aloneAfter.routeKey)
        assertEquals(aloneBefore.manifestId, aloneAfter.manifestId)
        assertEquals(aloneBefore.socketPath, aloneAfter.socketPath)
    }

    @Test
    fun `stable socket names fit Android filesystem Unix path budget`() {
        val endpoints = listOf(
            classify(AndroidAudioDeviceType.WIRED_HEADSET, "input", true, true)!!,
            classify(AndroidAudioDeviceType.AUX_LINE, "output", false, true, address = "rear-line-with-a-very-long-address")!!,
            classify(AndroidAudioDeviceType.BUILTIN_MIC, "input", true, false, address = "bottom")!!,
            classify(AndroidAudioDeviceType.BUILTIN_SPEAKER, "output", false, true)!!,
        )
        val longestRuntimePrefix = "/data/user/21474/com.tx5dr.bridge/files/runtime/tx5dr-data/runtime/sockets/"

        endpoints.forEach { endpoint ->
            val basename = endpoint.socketPath.substringAfterLast('/')
            assertTrue("Unexpected socket name: $basename", basename.matches(Regex("a[io]-[0-9a-f]{16}\\.sock")))
            assertTrue("Unix socket path exceeds conservative budget", (longestRuntimePrefix + basename).toByteArray().size < 100)
        }
    }

    @Test
    fun `default policy preserves USB then builtin priority and ignores unavailable`() {
        val wired = classify(AndroidAudioDeviceType.WIRED_HEADSET, "input", true, true)!!
        val builtin = classify(AndroidAudioDeviceType.BUILTIN_MIC, "input", true, false)!!
        val usb = classify(AndroidAudioDeviceType.USB_DEVICE, "input", true, true)!!
        assertEquals(usb.routeKey, AndroidAudioDevicePolicy.defaultEndpoint(listOf(wired, builtin, usb), "input")?.routeKey)
        assertEquals(builtin.routeKey, AndroidAudioDevicePolicy.defaultEndpoint(listOf(wired, builtin), "input")?.routeKey)
        assertEquals(wired.routeKey, AndroidAudioDevicePolicy.defaultEndpoint(listOf(wired, builtin.copy(available = false)), "input")?.routeKey)
    }

    @Test
    fun `manifest fields distinguish idle verified and failed states`() {
        val idle = classify(AndroidAudioDeviceType.WIRED_HEADSET, "output", true, true)!!
        val verified = idle.copy(clientConnected = true, routeState = AudioRouteState.VERIFIED, routeVerified = true)
        val failed = idle.copy(routeState = AudioRouteState.LOST, failureReason = "route changed")
        val idleFields = AndroidAudioDevicePolicy.manifestFields(idle, false)
        val verifiedFields = AndroidAudioDevicePolicy.manifestFields(verified, true)
        val failedFields = AndroidAudioDevicePolicy.manifestFields(failed, false)
        assertEquals(AudioRouteState.IDLE, idleFields["routeState"])
        assertFalse(idleFields["routeVerified"] as Boolean)
        assertTrue(verifiedFields["clientConnected"] as Boolean)
        assertTrue(verifiedFields["routeVerified"] as Boolean)
        assertEquals("route changed", failedFields["failureReason"])
        assertEquals("3.5mm", failedFields["connector"])
    }

    @Test
    fun `input capabilities pin bridge rate while output keeps header rate configurable`() {
        val input = classify(AndroidAudioDeviceType.WIRED_HEADSET, "input", true, true)!!.copy(sampleRates = listOf(44_100, 48_000))
        val output = classify(AndroidAudioDeviceType.WIRED_HEADSET, "output", true, true)!!.copy(sampleRates = listOf(44_100, 48_000))
        val inputCapabilities = AndroidAudioDevicePolicy.capabilityFields(input)
        val outputCapabilities = AndroidAudioDevicePolicy.capabilityFields(output)

        assertEquals(false, inputCapabilities["sampleRateConfigurable"])
        assertEquals(48_000, inputCapabilities["fixedSampleRate"])
        assertEquals(true, outputCapabilities["sampleRateConfigurable"])
        assertNull(outputCapabilities["fixedSampleRate"])
        assertEquals(true, outputCapabilities["outputRouteAck"])
    }

    @Test
    fun `owner release resets available endpoint but preserves unavailable state`() {
        val endpoint = classify(AndroidAudioDeviceType.WIRED_HEADSET, "input", true, true)!!.copy(
            clientConnected = true,
            routeState = AudioRouteState.VERIFIED,
            routeVerified = true,
            failureReason = "old failure",
            audioSource = "UNPROCESSED",
        )
        val reset = AndroidAudioDevicePolicy.resetReleasedEndpoint(endpoint)
        assertFalse(reset.clientConnected)
        assertEquals(AudioRouteState.IDLE, reset.routeState)
        assertFalse(reset.routeVerified)
        assertNull(reset.failureReason)
        assertNull(reset.audioSource)

        val unavailable = endpoint.copy(available = false, routeState = AudioRouteState.UNAVAILABLE)
        assertEquals(unavailable, AndroidAudioDevicePolicy.resetReleasedEndpoint(unavailable))
    }

    @Test
    fun `disconnect keeps stable analog endpoint unavailable and replug clears stale failure`() {
        val detected = classify(AndroidAudioDeviceType.WIRED_HEADSET, "input", true, true)!!
        val active = detected.copy(
            clientConnected = true,
            routeState = AudioRouteState.VERIFIED,
            routeVerified = true,
            audioSource = "UNPROCESSED",
        )

        val disconnected = AndroidAudioDevicePolicy.mergeEndpointState("input", emptyList(), listOf(active)).single()
        assertFalse(disconnected.available)
        assertEquals(AudioRouteState.UNAVAILABLE, disconnected.routeState)
        assertFalse(disconnected.routeVerified)

        val replugged = AndroidAudioDevicePolicy.mergeEndpointState("input", listOf(detected.copy(id = 44)), listOf(disconnected)).single()
        assertTrue(replugged.available)
        assertEquals(AudioRouteState.IDLE, replugged.routeState)
        assertNull(replugged.failureReason)
        assertNull(replugged.audioSource)
    }

    @Test
    fun `analog and builtin input use raw fallback policy while USB stays default`() {
        val rawPriority = listOf(AndroidAudioCaptureProfile.UNPROCESSED, AndroidAudioCaptureProfile.VOICE_RECOGNITION, AndroidAudioCaptureProfile.DEFAULT)
        assertEquals(rawPriority, AndroidAudioDevicePolicy.captureProfiles(AudioRoute.WIRED_HEADSET))
        assertEquals(rawPriority, AndroidAudioDevicePolicy.captureProfiles(AudioRoute.ANALOG_LINE))
        assertEquals(rawPriority, AndroidAudioDevicePolicy.captureProfiles(AudioRoute.BUILTIN_MIC))
        assertEquals(listOf(AndroidAudioCaptureProfile.DEFAULT), AndroidAudioDevicePolicy.captureProfiles(AudioRoute.USB))
    }

    private fun classify(
        type: Int,
        direction: String,
        isSource: Boolean,
        isSink: Boolean,
        id: Int = 9,
        address: String = "",
    ): AndroidAudioEndpoint? = AndroidAudioDevicePolicy.classify(
        AndroidAudioDeviceDescriptor(
            androidDeviceId = id,
            type = type,
            isSource = isSource,
            isSink = isSink,
            direction = direction,
            productName = "Test endpoint",
            address = address,
            sampleRates = listOf(48_000),
            channelCounts = listOf(1),
        )
    )
}
