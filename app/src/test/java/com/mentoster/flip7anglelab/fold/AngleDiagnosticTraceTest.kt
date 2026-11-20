package com.mentoster.flip7anglelab.fold

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AngleDiagnosticTraceTest {
    private fun frame(
        nanos: Long,
        reason: String = "tracking",
        publicHinge: Float? = 180f,
        gyroSpeed: Float = 0.002f,
        adaptive: Float = 179f,
        shown: Float = 180f,
        held: Boolean = false,
        uncertain: Boolean = false,
        magneticX: Float = 100f,
    ) = AngleDiagnosticTrace.Frame(
        timestampNanos = nanos,
        magnet = AngleDiagnosticTrace.Vector(magneticX, 180f, -260f),
        gravity = AngleDiagnosticTrace.Vector(0.1f, 0.2f, 9.8f),
        gyro = AngleDiagnosticTrace.Vector(gyroSpeed, 0f, 0f),
        gravityAgeMs = 4L,
        gyroAgeMs = 8L,
        hingeAgeMs = 10_000L,
        publicHinge = publicHinge,
        reference = 179f,
        referenceAgeMs = 5_000L,
        boost = 176f,
        offlineSmoothed = 180f,
        adaptive = adaptive,
        matchMode = "strict",
        guardReason = reason,
        guardAngle = adaptive,
        stabilized = adaptive,
        displayed = shown,
        held = held,
        uncertain = uncertain,
        immediate = false,
        reanchored = false,
        coarseFallback = false,
        magnetometerHz = 125f,
        gyroscopeHz = 50f,
        learnedSamples = 1500,
    )

    @Test
    fun capturesEntireDecisionChainAndSensorFreshness() {
        val logger = AngleDiagnosticTrace()
        val output = requireNotNull(logger.offer(frame(1_000_000_000L)))
        assertTrue(output.startsWith("event=transition tNs=1000000000"))
        for (field in listOf(
            "reason=tracking", "public=180.00", "hingeAgeMs=10000",
            "samsung=179.00", "samsungAgeMs=5000",
            "mag=(100.00,180.00,-260.00)", "magNorm=", "magStep=null",
            "magGravityProjection=", "magPerpendicular=",
            "grav=(0.10,0.20,9.80)", "gravAgeMs=4",
            "gyro=(0.00,0.00,0.00)", "gyroAgeMs=8",
            "boostRaw=176.00", "offline=180.00", "knn=179.00",
            "modelDisagreement=3.00",
            "match=strict", "gate=179.00", "stabilized=179.00",
            "display=180.00", "displaySource=ml", "referenceError=null",
            "rotationHeld=false", "adaptiveHeld=false",
            "magHz=125.00", "gyroHz=50.00",
            "learned=1500",
        )) {
            assertTrue("Missing $field", output.contains(field))
        }
    }

    @Test
    fun idleLogsAreThrottledButMotionAndReasonChangesAreVisible() {
        val trace = AngleDiagnosticTrace()
        val t = 2_000_000_000L
        assertNotNull(trace.offer(frame(t)))
        assertNull(trace.offer(frame(t + 10_000_000L)))
        assertNull(trace.offer(frame(t + 900_000_000L)))
        assertTrue(requireNotNull(trace.offer(frame(t + 1_000_000_000L)))
            .startsWith("event=idle-sample"))

        val rotation = requireNotNull(trace.offer(frame(
            t + 1_050_000_000L,
            reason = "rotation-hold-start", gyroSpeed = 1f,
            held = true, adaptive = 143f, shown = 180f,
        )))
        assertTrue(rotation.contains("event=transition"))
        assertTrue(rotation.contains("reason=rotation-hold-start"))
        assertTrue(rotation.contains("magStep="))
        assertNull(trace.offer(frame(
            t + 1_060_000_000L,
            reason = "rotation-hold-start", gyroSpeed = 1f, held = true,
        )))
        val released = trace.offer(frame(
            t + 1_100_000_000L,
            reason = "rotation-hold-expired", gyroSpeed = 1f,
            shown = 140f, adaptive = 140f, uncertain = true,
        ))
        assertNotNull(released)
        assertTrue(released!!.contains("uncertain=true"))
        assertTrue(released.contains("reason=rotation-hold-expired"))
        assertNull(trace.offer(frame(t + 1_110_000_000L,
            reason = "rotation-uncertain-live", gyroSpeed = 1f, shown = 135f)))
        assertTrue(requireNotNull(trace.offer(frame(
            t + 1_260_000_000L,
            reason = "rotation-uncertain-live", gyroSpeed = 1f,
            shown = 127f, uncertain = true,
        ))).contains("event=transition"))
    }

    @Test
    fun coarseHingeTransitionLogsWithoutWaitingForOneSecond() {
        val logger = AngleDiagnosticTrace()
        val start = 5_000_000_000L
        logger.offer(frame(start, publicHinge = 90f, shown = 90f))
        val transition = requireNotNull(logger.offer(frame(
            start + 50_000_000L,
            reason = "hinge-transition", publicHinge = 180f,
            shown = 165f,
        )))
        assertTrue(transition.contains("reason=hinge-transition"))
        assertTrue(transition.contains("public=180.00"))
        assertTrue(transition.contains("display=165.00"))
    }

    @Test
    fun referenceIsUsedAsDisplaySourceOnlyWhenTimestampIsFresh() {
        val logger = AngleDiagnosticTrace()
        val base = frame(8_000_000_000L)
        val fresh = logger.offer(base.copy(
            referenceAgeMs = 50L, referenceTrusted = true, displayed = 179f,
        ))
        assertTrue(requireNotNull(fresh).contains("displaySource=samsung-on-change"))
        assertTrue(fresh.contains("referenceError=0.00"))
        val stale = logger.offer(base.copy(
            timestampNanos = 9_000_000_000L,
            referenceAgeMs = 800L, referenceTrusted = false, displayed = 173f,
        ))
        assertTrue(requireNotNull(stale).contains("displaySource=ml"))
        assertTrue(stale.contains("referenceError=null"))
    }

    @Test
    fun onChangeSamsungValueRemainsTrustedAfterSecondsWhileReaderIsConnected() {
        val logger = AngleDiagnosticTrace()
        val sample = logger.offer(frame(12_000_000_000L).copy(
            referenceTrusted = true,
            referenceAgeMs = 45_000L,
            displayed = 180f,
        ))
        assertTrue(requireNotNull(sample).contains("displaySource=samsung-on-change"))
        assertTrue(sample.contains("samsungAgeMs=45000"))
        assertTrue(sample.contains("referenceError=1.00"))
    }

    @Test
    fun outOfOrderSensorTimestampsNeverProduceExtraLines() {
        val logger = AngleDiagnosticTrace()
        assertNotNull(logger.offer(frame(10_000_000_000L)))
        assertNull(logger.offer(frame(10_000_000_000L)))
        assertNull(logger.offer(frame(9_000_000_000L)))
        val output = requireNotNull(logger.offer(frame(11_000_000_000L, magneticX = 113f)))
        assertTrue(output.contains("magStep=13.00"))
        assertFalse(output.contains("magStep=0.00"))
    }
}
