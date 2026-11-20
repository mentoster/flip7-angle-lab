package com.mentoster.flip7anglelab.fold

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AngleEstimateStabilizerTest {
    @Test
    fun stationarySubDegreeNoiseDoesNotChangeDisplay() {
        val smoother = AngleEstimateStabilizer()
        var timestamp = 1_000_000_000L
        smoother.update(timestamp, 91.5f, 30f, 90f)
        repeat(120) { index ->
            timestamp += 10_000_000L
            val jitter = if (index % 2 == 0) 91.1f else 91.9f
            val result = smoother.update(timestamp, jitter, 30f, 90f)
            assertEquals(91.5f, result.angle!!, 0.001f)
            assertTrue(result.adaptive)
        }
    }

    @Test
    fun isolatedLargeSpikeIsRejectedByMedian() {
        val smoother = AngleEstimateStabilizer()
        val start = 2_000_000_000L
        repeat(3) { smoother.update(start + it * 10_000_000L, 90f, 30f, 90f) }
        val spike = smoother.update(start + 30_000_000L, 160f, 30f, 90f)
        assertEquals(90f, spike.angle!!, 0.01f)
        val settled = smoother.update(start + 40_000_000L, 90f, 30f, 90f)
        assertEquals(90f, settled.angle!!, 0.01f)
    }

    @Test
    fun realFoldMotionStillTracksWithinAQuarterSecond() {
        val smoother = AngleEstimateStabilizer()
        var timestamp = 3_000_000_000L
        repeat(3) {
            smoother.update(timestamp, 90f, 30f, 90f)
            timestamp += 10_000_000L
        }
        var last = 90f
        repeat(15) {
            last = smoother.update(timestamp, 155f, 30f, 90f).angle!!
            timestamp += 15_000_000L
        }
        assertTrue("Angle lagged behind intentional folding: $last", last >= 145f)
        assertTrue("Angle overshot: $last", last <= 155f)
    }

    @Test
    fun briefAdaptiveDropoutDoesNotJumpToWrongOfflineModel() {
        val smoother = AngleEstimateStabilizer()
        val start = 4_000_000_000L
        repeat(3) { smoother.update(start + it * 10_000_000L, 179f, 145f, 180f) }
        val dropped = smoother.update(start + 50_000_000L, null, 145f, 180f)
        assertTrue(dropped.adaptive)
        assertEquals(179f, dropped.angle!!, 0.001f)
        val changedHinge = smoother.update(start + 60_000_000L, null, 50f, 90f)
        assertFalse(changedHinge.adaptive)
        assertTrue(changedHinge.angle!! > 145f)
    }

    @Test
    fun persistentMissingAdaptiveEventuallyUsesFallback() {
        val smoother = AngleEstimateStabilizer()
        val start = 5_000_000_000L
        repeat(3) { smoother.update(start + it * 10_000_000L, 179f, 145f, 180f) }
        val result = smoother.update(start + 150_000_000L, null, 145f, 180f)
        assertFalse(result.adaptive)
        var estimate = requireNotNull(result.angle)
        repeat(14) { index ->
            estimate = requireNotNull(
                smoother.update(start + 170_000_000L + index * 15_000_000L, null, 145f, 180f).angle,
            )
        }
        assertTrue("Fallback was never followed: $estimate", estimate < 150f)
    }

    @Test
    fun sustainedNearOpenAdaptivePredictionReachesExactly180DespiteDeadband() {
        val smoother = AngleEstimateStabilizer()
        var timestamp = 6_000_000_000L
        // Captured Flip7: Samsung=179, adaptive=178.4, filtered=177.8.
        val first = smoother.update(timestamp, 178.35f, 145f, 180f)
        assertTrue(first.angle!! < 180f)
        repeat(20) {
            timestamp += 20_000_000L
            smoother.update(timestamp, 178.35f + if (it % 2 == 0) 0.06f else -0.04f, 145f, 180f)
        }
        val snapped = smoother.update(timestamp + 20_000_000L, 178.37f, 145f, 180f)
        assertTrue(snapped.adaptive)
        assertEquals(180f, snapped.angle!!, 0f)
        // Tiny jitter should not immediately unsnap.
        assertEquals(180f, smoother.update(timestamp + 40_000_000L, 176f, 145f, 180f).angle!!, 0f)
    }

    @Test
    fun coarse180AtReal160DoesNotTriggerEndpoint() {
        val smoother = AngleEstimateStabilizer()
        var timestamp = 7_000_000_000L
        repeat(30) {
            val estimate = smoother.update(timestamp, 163f, 145f, 180f)
            assertTrue("Incorrect open-end snap: ${estimate.angle}", estimate.angle!! < 179f)
            timestamp += 20_000_000L
        }
    }

    @Test
    fun offlinePredictionOrSingleAdaptiveSpikeCannotTriggerOpenSnap() {
        val smoother = AngleEstimateStabilizer()
        var timestamp = 8_000_000_000L
        repeat(25) {
            assertTrue(smoother.update(timestamp, null, 178.5f, 180f).angle!! < 180f)
            timestamp += 20_000_000L
        }
        // Transient kNN false-positive returns to real 160° before dwell.
        smoother.update(timestamp, 178.5f, 145f, 180f)
        timestamp += 20_000_000L
        smoother.update(timestamp, 160f, 145f, 180f)
        timestamp += 20_000_000L
        assertTrue(smoother.update(timestamp, 160f, 145f, 180f).angle!! < 180f)
    }

    @Test
    fun leavingOpenEndpointReleasesSnap() {
        val smoother = AngleEstimateStabilizer()
        var timestamp = 9_000_000_000L
        repeat(20) {
            smoother.update(timestamp, 178.4f, 145f, 180f)
            timestamp += 20_000_000L
        }
        assertEquals(180f, smoother.update(timestamp, 178.4f, 145f, 180f).angle!!, 0f)
        // A real fold changes the coarse Android hinge state and the adaptive estimate.
        val moving = smoother.update(timestamp + 20_000_000L, 148f, 40f, 90f)
        assertTrue(moving.angle!! < 180f)
        assertTrue(moving.adaptive)
    }

    @Test
    fun rotationWithoutMatchingPoseFallsBackToLiveBoostAfterShortGrace() {
        val smoother = AngleEstimateStabilizer()
        val start = 10_000_000_000L
        repeat(5) {
            smoother.update(
                start + it * 10_000_000L, 102f, 30f, 90f, hasLearnedSamples = true,
            )
        }
        repeat(20) { index ->
            val output = smoother.update(
                start + 100_000_000L + index * 500_000_000L,
                null, 30f, 90f, hasLearnedSamples = true,
            )
            assertFalse("No calibration match must not freeze a live model for seconds", output.held)
            assertFalse(output.adaptive)
            assertTrue("Live Boost prediction must keep moving toward 30°", output.angle!! < 103f)
        }
    }

    @Test
    fun unseenPoseWithNoLastMatchUsesLiveOfflineBoost() {
        val smoother = AngleEstimateStabilizer()
        val start = 11_000_000_000L
        val first = smoother.update(start, null, 30f, 90f, hasLearnedSamples = true)
        assertFalse(first.coarseFallback)
        assertEquals(30f, first.angle!!, 0.01f)

        val switched = smoother.update(start + 100_000_000L, null, 130f, 180f, hasLearnedSamples = true)
        assertFalse(switched.coarseFallback)
        assertTrue(switched.angle!! > 30f)
    }

    @Test
    fun coarsePostureIsOnlyUsedWhenBothModelReadingsAreMissing() {
        val smoother = AngleEstimateStabilizer()
        val output = smoother.update(11_500_000_000L,
            adaptiveEstimate = null, offlineEstimate = null,
            publicHinge = 90f, hasLearnedSamples = true)
        assertTrue(output.coarseFallback)
        assertEquals(90f, output.angle!!, 0f)
    }

    @Test
    fun freshInstallWithoutLearningStillRunsOfflineMlp() {
        val smoother = AngleEstimateStabilizer()
        val sample = smoother.update(
            12_000_000_000L, null, 30f, 90f, hasLearnedSamples = false,
        )
        assertFalse(sample.held)
        assertFalse(sample.coarseFallback)
        assertEquals(30f, sample.angle!!, 0.01f)
    }

    @Test
    fun verifiedHingeMotionUsesNewAngleOnTheVeryFirstSensorEvent() {
        val smoother = AngleEstimateStabilizer()
        val start = 13_000_000_000L
        repeat(3) { smoother.update(start + it * 10_000_000L, 90f, 30f, 90f) }
        val opening = smoother.update(
            start + 30_000_000L,
            adaptiveEstimate = 145f,
            offlineEstimate = 130f,
            publicHinge = 90f,
            immediateMotion = true,
        )
        assertEquals("Instant path must bypass the median and EMA", 145f, opening.angle!!, 0f)
        val fullyOpen = smoother.update(
            start + 40_000_000L,
            adaptiveEstimate = 178.3f,
            offlineEstimate = 176f,
            publicHinge = 180f,
            immediateMotion = true,
        )
        assertEquals("Verified mechanical endpoint has no dwell", 180f, fullyOpen.angle!!, 0f)
        val closing = smoother.update(
            start + 50_000_000L,
            adaptiveEstimate = 133f,
            offlineEstimate = 125f,
            publicHinge = 90f,
            immediateMotion = true,
        )
        assertEquals("Closing also bypasses 3-frame delay", 133f, closing.angle!!, 0f)
    }

    @Test
    fun unchangedPostureWithoutFastMotionStillSuppressesIsolatedJitter() {
        val smoother = AngleEstimateStabilizer()
        val start = 14_000_000_000L
        repeat(3) { smoother.update(start + it * 10_000_000L, 90f, 90f, 90f) }
        assertEquals(
            90f,
            smoother.update(start + 30_000_000L, 119f, 90f, 90f).angle!!,
            0f,
        )
    }

    @Test
    fun instantHingeMotionUsesNewBoostWhenOldKnnMatchDropsOut() {
        val smoother = AngleEstimateStabilizer()
        val start = 15_000_000_000L
        smoother.update(start, 90f, 90f, 90f, hasLearnedSamples = true)
        val next = smoother.update(
            start + 15_000_000L,
            adaptiveEstimate = null,
            offlineEstimate = 124f,
            publicHinge = 90f,
            hasLearnedSamples = true,
            immediateMotion = true,
        )
        assertEquals("A stale learned sample cannot block folding", 124f, next.angle!!, 0f)
        assertFalse(next.held)
        assertFalse(next.coarseFallback)
    }

    @Test
    fun fullyOpenStationaryNeighborSwitchingCannotFlipBetween174And180() {
        val smoother = AngleEstimateStabilizer()
        var time = 16_000_000_000L
        repeat(24) {
            smoother.update(time, 178.4f, 175f, 180f)
            time += 20_000_000L
        }
        assertEquals(180f, smoother.update(time, 178.3f, 174f, 180f).angle!!, 0f)

        // Captured physical Samsung=177° at rest. The kNN alternates near
        // the release threshold as its nearest neighbor changes.
        repeat(300) { index ->
            time += 8_000_000L
            val noisyKnn = when (index % 4) {
                0 -> 173.7f
                1 -> 176.8f
                2 -> 178.1f
                else -> 173.9f
            }
            val result = smoother.update(time, noisyKnn, 173f, 180f)
            assertEquals(
                "A single borderline kNN switch must not release the 180° endpoint",
                180f, result.angle!!, 0f,
            )
        }
    }

    @Test
    fun trueFoldLeaves180WhenLowerAnglesAreSustained() {
        val smoother = AngleEstimateStabilizer()
        var time = 17_000_000_000L
        repeat(24) {
            smoother.update(time, 178.5f, 176f, 180f)
            time += 20_000_000L
        }
        assertEquals(180f, smoother.update(time, 178.5f, 176f, 180f).angle!!, 0f)
        time += 20_000_000L
        assertEquals(180f, smoother.update(time, 172.8f, 172f, 180f).angle!!, 0f)
        repeat(8) {
            time += 20_000_000L
            smoother.update(time, 172.8f, 172f, 180f)
        }
        assertTrue(
            "Persistent real departure must not remain stuck at 180°",
            requireNotNull(smoother.update(time + 20_000_000L, 172.8f, 172f, 180f).angle) < 180f,
        )
    }

    @Test
    fun evidencedHingeMotionReleasesOpenEndpointOnFirstSample() {
        val smoother = AngleEstimateStabilizer()
        var time = 18_000_000_000L
        repeat(24) {
            smoother.update(time, 178.4f, 176f, 180f)
            time += 20_000_000L
        }
        val result = smoother.update(
            time, adaptiveEstimate = 171f, offlineEstimate = 171f,
            publicHinge = 180f, immediateMotion = true,
        )
        assertEquals(171f, result.angle!!, 0f)
    }
}
