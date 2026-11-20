package com.mentoster.flip7anglelab.fold

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CalibrationV2GuideTest {
    @Test
    fun fullDynamicPlan_entersStaticSweep() {
        val guide = CalibrationV2Guide(cyclesPerStage = 1, holdNanos = 10L)
        var now = 1_000_000_000L
        guide.start(180f, now)

        repeat(CalibrationOrientation.entries.size * CalibrationSpeed.entries.size) {
            guide.confirmReady()
            now += 1_000_000_000L
            guide.update(160f, now)
            now += 1_000_000_000L
            guide.update(0f, now)
            now += 1_000_000_000L
            guide.update(20f, now)
            now += 1_000_000_000L
            guide.update(180f, now)
        }

        assertEquals(CalibrationPhase.STATIC_HOLD, guide.state.phase)
        assertEquals(18, guide.state.completedCycles)
        assertEquals(180, guide.state.staticTargetDegrees)
        assertTrue(guide.state.recordingTag.contains("mode=static"))
    }

    @Test
    fun staticHold_resetsWhenLeavingTolerance() {
        val guide = CalibrationV2Guide(cyclesPerStage = 1, holdNanos = 1_000L)
        var now = 1_000L
        guide.start(180f, now)
        repeat(18) {
            guide.confirmReady()
            guide.update(160f, ++now)
            guide.update(0f, ++now)
            guide.update(20f, ++now)
            guide.update(180f, ++now)
        }

        guide.update(180f, now + 100L)
        guide.update(180f, now + 200L)
        assertTrue(guide.state.holdProgress > 0f)
        guide.update(175f, now + 300L)
        assertEquals(0f, guide.state.holdProgress)
        assertTrue(guide.state.feedback.contains("Set 180"))
    }

    @Test
    fun stop_marksGuideIdle() {
        val guide = CalibrationV2Guide()
        guide.start(180f, 1L)
        val stopped = guide.stop()

        assertFalse(stopped.running)
        assertEquals(CalibrationPhase.IDLE, stopped.phase)
        assertEquals("cal-v2|idle", stopped.recordingTag)
    }
}
