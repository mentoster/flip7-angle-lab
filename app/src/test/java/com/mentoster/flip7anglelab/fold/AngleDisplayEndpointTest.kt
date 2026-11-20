package com.mentoster.flip7anglelab.fold

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AngleDisplayEndpointTest {
    @Test
    fun samsungFullyOpenRaw179IsDisplayedAs180() {
        assertEquals(180f, AngleDisplayEndpoint.fromSamsung(179f, 180f)!!, 0f)
        assertEquals(180f, AngleDisplayEndpoint.fromSamsung(177f, 180f)!!, 0f)
    }

    @Test
    fun rawSamsungAnglesAndCoarseAndroidStateAreNotOverwritten() {
        assertEquals(165f, AngleDisplayEndpoint.fromSamsung(165f, 180f)!!, 0f)
        assertEquals(176f, AngleDisplayEndpoint.fromSamsung(176f, 180f)!!, 0f)
        assertEquals(179f, AngleDisplayEndpoint.fromSamsung(179f, 90f)!!, 0f)
        assertNull(AngleDisplayEndpoint.fromSamsung(null, 180f))
    }
}
