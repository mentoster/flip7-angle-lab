package com.mentoster.flip7anglelab.fold

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SamsungEndpointPresentationTest {
    @Test
    fun onPhoneRotationSmallVendorNoiseDoesNotMake180JumpTo176() {
        val renderer = SamsungEndpointPresentation()
        assertEquals(180f, renderer.render(177f, 180f, false)!!, 0f)
        assertEquals(180f, renderer.render(176f, 180f, true)!!, 0f)
        assertEquals(180f, renderer.render(177f, 180f, true)!!, 0f)
        assertEquals(180f, renderer.render(176f, 180f, true)!!, 0f)
    }

    @Test
    fun genuineFoldBeyondNoiseBandIsImmediateEvenDuringBodyMotion() {
        val renderer = SamsungEndpointPresentation()
        renderer.render(177f, 180f, false)
        assertEquals(175f, renderer.render(175f, 180f, true)!!, 0f)
        assertEquals(174f, renderer.render(174f, 180f, true)!!, 0f)
        assertEquals(160f, renderer.render(160f, 180f, true)!!, 0f)
        assertEquals(92f, renderer.render(92f, 90f, true)!!, 0f)
    }

    @Test
    fun stationaryDeviceShowsActual176WithoutArtificialSnap() {
        val renderer = SamsungEndpointPresentation()
        renderer.render(177f, 180f, false)
        assertEquals(176f, renderer.render(176f, 180f, false)!!, 0f)
    }

    @Test
    fun staleOrDisconnectedSourceCannotKeepSnappedValue() {
        val renderer = SamsungEndpointPresentation()
        renderer.render(177f, 180f, false)
        assertNull(renderer.render(null, 180f, true))
        assertEquals(176f, renderer.render(176f, 180f, true)!!, 0f)
    }
}
