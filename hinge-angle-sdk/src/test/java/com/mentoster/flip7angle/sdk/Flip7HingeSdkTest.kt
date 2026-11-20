package com.mentoster.flip7angle.sdk

import java.io.File
import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Flip7HingeSdkTest {
    private fun model(): Flip7HingePredictor = File(
        "src/main/assets/flip7_hinge_boost_v2.b64",
    ).inputStream().use(Flip7HingePredictor::from)

    @Test
    fun aarBundlesReadyToUseOfflineBoostModel() {
        val predictor = model()
        assertEquals(Flip7HingePredictor.MODEL_VERSION, "boost-v2")
        assertEquals(120, predictor.treeCount)
        // Exact parity with the Python exporter, not an accuracy guarantee.
        val raw = predictor.predict(
            -43.38f, 98.04f, -110.10f, 0.09f, 9.72f, 1.29f, 180f,
        )
        assertEquals(177.2131f, requireNotNull(raw), 0.02f)
    }

    @Test
    fun malformedInputsAndModelBytesFailSafely() {
        val predictor = model()
        assertNull(predictor.predict(
            Float.NaN, 80f, 25f, 0f, 9.8f, 0f, 90f,
        ))
        assertNull(predictor.predict(
            20f, 80f, 25f, 0f, 0f, 0f, 90f,
        ))
        assertTrue(runCatching {
            ByteArrayInputStream("broken".toByteArray()).use(Flip7HingePredictor::from)
        }.isFailure)
    }

    @Test
    fun sdkNeverClaimsPrecisionWithMissingCoarseHinge() {
        val engine = HingeAngleEngine(model(), testedOnDevice = true)
        val frame = engine.frame(
            1_000_000_000L, -43.38f, 98.04f, -110.1f,
            .09f, 9.72f, 1.29f, 0f, null,
        )
        assertEquals(HingeAngleStatus.WAITING_FOR_SENSORS, frame.status)
        assertNull(frame.angleDegrees)
    }

    @Test
    fun rigidWholePhoneRotationHoldsAcceptedAngleThenResumesAfterCoarseHingeChange() {
        val engine = HingeAngleEngine(model(), testedOnDevice = true)
        val time = 2_000_000_000L
        val base = engine.frame(
            time, -43.38f, 98.04f, -110.1f,
            .09f, 9.72f, 1.29f, 0f, 180f,
        )
        assertEquals(HingeAngleStatus.LIVE_ESTIMATE, base.status)
        val accepted = requireNotNull(base.angleDegrees)
        assertTrue(accepted in 150f..180f)
        repeat(100) { index ->
            val held = engine.frame(
                time + 8_000_000L * (index + 1),
                -43.38f + index * .12f, 98.04f, -110.1f,
                .09f, 9.72f, 1.29f, 2.5f, 180f,
            )
            assertEquals("Whole-phone rotation must be labeled", HingeAngleStatus.HELD_DURING_ROTATION, held.status)
            assertEquals(accepted, requireNotNull(held.angleDegrees), 0f)
            assertTrue(held.held)
            assertNotNull("Raw ML must stay live while the output is held", held.rawModelDegrees)
        }
        val hingeChanged = engine.frame(
            time + 820_000_000L,
            -40f, 99f, -109f, .09f, 9.72f, 1.29f,
            2.5f, 90f,
        )
        assertEquals(HingeAngleStatus.LIVE_ESTIMATE, hingeChanged.status)
        assertFalse(hingeChanged.held)
        assertEquals(90f, hingeChanged.coarseHingeDegrees!!, 0f)
        assertTrue(hingeChanged.reason.startsWith("hinge-transition"))
    }

    @Test
    fun explicitStatusPreventsHeldEstimateFromPretendingToBeNewMeasurement() {
        val engine = HingeAngleEngine(model(), testedOnDevice = false)
        val time = 3_000_000_000L
        engine.frame(time, -43.38f, 98.04f, -110.1f, .09f, 9.72f, 1.29f, 0f, 180f)
        val rotating = engine.frame(
            time + 10_000_000L, -43f, 98f, -110f,
            .09f, 9.72f, 1.29f, 1f, 180f,
        )
        assertTrue(rotating.held)
        assertFalse(rotating.testedOnDevice)
    }
}
