package com.mentoster.flip7anglelab.fold

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SamsungAngleReferencePolicyTest {
    @Test
    fun eventDrivenReferenceDoesNotExpireAt350msWhenReaderRemainsActive() {
        // On a stationary phone the SensorsHub may stay silent for hours.
        val recorded = SamsungAngleSample(177f, timestampNanos = 1L)
        val raw = SamsungAngleReferencePolicy.trustedAngle(
            recorded, sourceActive = true, publicHinge = 180f,
        )
        assertEquals(177f, raw!!, 0f)
        assertEquals(180f, AngleDisplayEndpoint.fromSamsung(raw, 180f)!!, 0f)
    }

    @Test
    fun disconnectedReaderCannotFreezeOldExactValue() {
        assertNull(SamsungAngleReferencePolicy.trustedAngle(
            SamsungAngleSample(177f, 1L), sourceActive = false, publicHinge = 180f,
        ))
        assertNull(SamsungAngleReferencePolicy.trustedAngle(
            null, sourceActive = true, publicHinge = 180f,
        ))
    }

    @Test
    fun coarsePostureIsUsedOnlyToRejectContradictionsNotToForgePrecision() {
        val halfway = SamsungAngleSample(155f, 1L)
        assertEquals(155f, SamsungAngleReferencePolicy.trustedAngle(
            halfway, sourceActive = true, publicHinge = 180f,
        )!!, 0f)
        assertEquals(155f, AngleDisplayEndpoint.fromSamsung(155f, 180f)!!, 0f)
        assertNull(SamsungAngleReferencePolicy.trustedAngle(
            SamsungAngleSample(80f, 1L), sourceActive = true, publicHinge = 180f,
        ))
        assertNull(SamsungAngleReferencePolicy.trustedAngle(
            SamsungAngleSample(177f, 1L), sourceActive = true, publicHinge = 0f,
        ))
    }
}
