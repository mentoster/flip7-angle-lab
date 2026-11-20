package com.mentoster.flip7anglelab.fold

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Reproduces a 178°->156° false reading during rigid whole-phone rotation. */
class LiveAnglePipelineRegressionTest {
    @Test
    fun motionCannotBecomeFakeFoldingButConfirmedHingeMotionMustRemainInstant() {
        val guard = OrientationMotionGuard()
        val stabilizer = AngleEstimateStabilizer()
        var displayed: Float? = null
        fun frame(time: Long, model: Float, gyro: Float, coarse: Float): Pair<Float, Boolean> {
            val gated = guard.update(
                timestampNanos = time,
                magneticX = 100f,
                magneticY = 180f,
                magneticZ = -260f,
                gravityX = 0f,
                gravityY = 0f,
                gravityZ = 9.81f,
                gyroSpeedRadiansPerSecond = gyro,
                publicHinge = coarse,
                modelAngle = model,
                displayedAngle = displayed,
                adaptiveAngle = model,
                offlineRawAngle = model,
                adaptiveMatchMode = "strict",
            )
            if (gated.reanchored && !gated.uncertain) stabilizer.reset()
            val immediate = gated.hingeMotionEvidence ||
                gated.reason == "rotation-endpoint-consensus"
            val stabilized = stabilizer.update(
                timestampNanos = time,
                adaptiveEstimate = if (gated.held) gated.angle else model,
                offlineEstimate = model,
                publicHinge = coarse,
                hasLearnedSamples = true,
                immediateMotion = immediate,
            )
            displayed = if (gated.held) gated.angle else stabilized.angle
            return requireNotNull(displayed) to gated.held
        }
        val start = 1_000_000_000L
        assertEquals(90f, frame(start, 90f, 0.002f, 90f).first, 0f)
        assertTrue(frame(start + 10_000_000L, 110f, 1f, 90f).second)
        assertTrue(frame(start + 30_000_000L, 125f, 1f, 90f).second)
        val (unfrozen, held) = frame(start + 60_000_000L, 143f, 1f, 90f)
        assertTrue("Rigid rotation must keep last measured hinge", held)
        assertEquals(90f, unfrozen, 0f)
        val next = frame(start + 70_000_000L, 153f, 1f, 90f).first
        assertEquals(90f, next, 0f)
        assertEquals(180f, frame(start + 80_000_000L, 178.4f, 1f, 180f).first, 0f)
    }
}
