package com.mentoster.flip7anglelab.fold

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OrientationMotionGuardTest {
    private fun OrientationMotionGuard.frame(
        time: Long,
        estimate: Float,
        previous: Float = estimate,
        publicHinge: Float = 90f,
        magneticX: Float = 100f,
        gravityY: Float = 0f,
        gravityZ: Float = 9.81f,
        gyroSpeed: Float? = 0.01f,
        offlineRaw: Float? = null,
        adaptiveAngle: Float? = null,
        matchMode: String = "none",
    ): OrientationMotionGuard.Output = update(
        timestampNanos = time,
        magneticX = magneticX,
        magneticY = 180f,
        magneticZ = -260f,
        gravityX = 0f,
        gravityY = gravityY,
        gravityZ = gravityZ,
        gyroSpeedRadiansPerSecond = gyroSpeed,
        publicHinge = publicHinge,
        modelAngle = estimate,
        displayedAngle = previous,
        offlineRawAngle = offlineRaw,
        adaptiveAngle = adaptiveAngle,
        adaptiveMatchMode = matchMode,
    )

    @Test
    fun stationaryPhoneCanFollowRealFoldingWithNoGyroMovement() {
        val guard = OrientationMotionGuard()
        val first = guard.frame(1_000_000_000L, 90f)
        assertFalse(first.held)
        val mid = guard.frame(1_100_000_000L, 115f, previous = 90f, magneticX = 145f)
        val latest = guard.frame(1_200_000_000L, 145f, previous = 115f, magneticX = 210f)
        assertFalse(mid.held)
        assertFalse(latest.held)
        assertEquals(115f, mid.angle!!, 0f)
        assertEquals(145f, latest.angle!!, 0f)
    }

    @Test
    fun rotatingWholePhoneNeverLeaksPoseDependentModelIntoHingeAngle() {
        val guard = OrientationMotionGuard()
        val initial = 2_000_000_000L
        guard.frame(initial, 90f)
        repeat(20) { index ->
            val time = initial + (index + 1) * 20_000_000L
            val output = guard.frame(
                time,
                125f + index % 12,
                previous = 90f,
                magneticX = 100f + index % 5,
                gravityY = 3f + index * .25f,
                gravityZ = 9.3f - index * .25f,
                gyroSpeed = 0.6f,
            )
            assertTrue("Rotation is not evidence of hinge movement", output.held)
            assertTrue("An old angle must be labeled as held", output.uncertain)
            assertEquals(90f, output.angle!!, 0f)
        }
        // The phone stopped in a DIFFERENT pose, so its new ~135° ML result
        // is not evidence that the hinge moved from its actual 90° setting.
        repeat(20) { index ->
            val output = guard.frame(
                initial + 500_000_000L + index * 50_000_000L,
                135f,
                previous = 90f,
                magneticX = 103f,
                gravityY = 7.75f,
                gravityZ = 4.55f,
            )
            assertTrue("A new whole-phone pose must not unlock a false model angle", output.held)
            assertEquals(90f, output.angle!!, 0f)
        }
    }

    @Test
    fun newFoldingMotionAfterRotationReleasesHold() {
        val guard = OrientationMotionGuard()
        val start = 3_000_000_000L
        guard.frame(start, 90f)
        guard.frame(start + 20_000_000L, 140f, previous = 90f, gyroSpeed = 1f)
        guard.frame(
            start + 100_000_000L,
            140f,
            previous = 90f,
            gravityY = 6f,
            gravityZ = 7f,
            magneticX = 110f,
            gyroSpeed = 1f,
        )
        val stillHeld = guard.frame(
            start + 700_000_000L,
            140f,
            previous = 90f,
            gravityY = 6f,
            gravityZ = 7f,
            magneticX = 110f,
        )
        assertTrue("After rotation, a changed ML guess is not hinge evidence", stillHeld.held)
        assertEquals(90f, stillHeld.angle!!, 0f)
        // The base half is stable and a clear new local magnetic change
        // provides evidence of hinge motion.
        val folded = guard.frame(
            start + 750_000_000L,
            113f,
            previous = 90f,
            gravityY = 6f,
            gravityZ = 7f,
            magneticX = 150f,
        )
        assertFalse(folded.held)
        assertEquals(113f, folded.angle!!, 0f)
    }

    @Test
    fun changingCoarseHingePostureReleasesRotationHold() {
        val guard = OrientationMotionGuard()
        val start = 4_000_000_000L
        guard.frame(start, 145f, publicHinge = 180f)
        assertTrue(
            guard.frame(start + 20_000_000L, 160f, 145f, publicHinge = 180f, gyroSpeed = 1f).held,
        )
        val changed = guard.frame(
            start + 40_000_000L,
            95f,
            previous = 145f,
            publicHinge = 90f,
            gyroSpeed = 0f,
        )
        assertFalse(changed.held)
        assertTrue("Hinge posture change must be immediate", changed.immediate)
        assertTrue("Hinge posture change is hinge-motion evidence", changed.hingeMotionEvidence)
        assertEquals(95f, changed.angle!!, 0f)
    }

    @Test
    fun fastMagneticHingeMotionReleasesRotationHoldWithoutDwell() {
        val guard = OrientationMotionGuard()
        val start = 4_500_000_000L
        guard.frame(start, 90f, magneticX = 100f)
        val hold = guard.frame(
            start + 10_000_000L,
            110f,
            previous = 90f,
            magneticX = 101f,
            gyroSpeed = 0.6f,
        )
        assertTrue(hold.held)
        assertEquals("rotation-hold-start", hold.reason)
        val fold = guard.frame(
            start + 20_000_000L,
            122f,
            previous = 90f,
            magneticX = 125f,
            gyroSpeed = 0.6f,
        )
        assertFalse("Real magnetic hinge motion must release immediately", fold.held)
        assertTrue(fold.immediate)
        assertTrue("A large local field step is heuristic hinge evidence", fold.hingeMotionEvidence)
        assertEquals("magnetic-step-fold", fold.reason)
        assertEquals(122f, fold.angle!!, 0f)
    }

    @Test
    fun slowTiltCanBeDetectedWithoutGyroscopeThreshold() {
        val guard = OrientationMotionGuard()
        val start = 5_000_000_000L
        guard.frame(start, 90f)
        // Incremental tilt is not ignored by repeatedly resetting the
        // original gravity anchor on each small movement.
        guard.frame(
            start + 100_000_000L,
            91f, previous = 90f, gravityY = 0.45f, gravityZ = 9.79f,
        )
        val tilted = guard.frame(
            start + 300_000_000L,
            120f, previous = 91f, gravityY = 2f, gravityZ = 9.6f,
            gyroSpeed = 0.02f,
        )
        assertTrue(tilted.held)
        assertEquals(91f, tilted.angle!!, 0f)
    }

    @Test
    fun yawRotationIsDetectedEvenWhenGravityIsUnchanged() {
        val guard = OrientationMotionGuard()
        val start = 6_000_000_000L
        guard.frame(start, 178f, publicHinge = 180f)
        val yaw = guard.frame(
            start + 20_000_000L, 140f, 178f, publicHinge = 180f,
            gyroSpeed = 0.4f,
        )
        assertTrue(yaw.held)
        assertEquals(178f, yaw.angle!!, 0f)
    }

    @Test
    fun incrementalSlowRotationCannotAccumulateHiddenAngleDrift() {
        val guard = OrientationMotionGuard()
        val start = 7_000_000_000L
        guard.frame(start, 90f)
        var previous = 90f
        var held = false
        var heldSince = 0L
        repeat(30) { index ->
            val tilt = Math.toRadians((index + 1).toDouble()).toFloat()
            val estimate = 90f + (index + 1) * 0.65f
            val result = guard.frame(
                start + (index + 1) * 120_000_000L,
                estimate,
                previous = previous,
                gravityY = (9.81 * kotlin.math.sin(tilt)).toFloat(),
                gravityZ = (9.81 * kotlin.math.cos(tilt)).toFloat(),
                gyroSpeed = 0.015f,
            )
            if (result.held) {
                held = true
                if (heldSince == 0L) heldSince = start + (index + 1) * 120_000_000L
                assertTrue("Transient hold must remain short", result.angle!! < 97f)
            }
            if (result.held) assertTrue(result.uncertain)
            previous = result.angle ?: previous
        }
        assertTrue("Slow pose drift must eventually be detected", held)
        assertTrue("A changed pose cannot become a new hinge angle", previous <= 97f)
    }

    @Test
    fun wrongStartupHoldAt31RecoversWhenBothModelsConfirmFullyOpen() {
        val guard = OrientationMotionGuard()
        val start = 8_000_000_000L
        guard.frame(start, 31f, publicHinge = 180f)
        val held = guard.frame(
            start + 20_000_000L,
            178f, previous = 31f, publicHinge = 180f, gyroSpeed = 0.6f,
            offlineRaw = 178f, adaptiveAngle = 179f, matchMode = "strict",
        )
        assertTrue(held.held)
        assertEquals(31f, held.angle!!, 0f)

        var recovered: OrientationMotionGuard.Output = held
        repeat(80) { index ->
            recovered = guard.frame(
                start + 60_000_000L + index * 20_000_000L,
                178.5f, previous = 31f, publicHinge = 180f,
                gyroSpeed = 0.002f,
                offlineRaw = 178f, adaptiveAngle = 178.5f, matchMode = "strict",
            )
        }
        assertFalse("Recovery from 31° must never stall indefinitely", recovered.held)
        assertTrue("Held angle should reacquire ~179°", recovered.angle!! >= 177f)
    }

    @Test
    fun coarse180AloneCannotUnlockHeldValueWhenRotationContinues() {
        val guard = OrientationMotionGuard()
        val start = 9_000_000_000L
        guard.frame(start, 155f, publicHinge = 180f)
        guard.frame(start + 20_000_000L, 180f, 155f, 180f, gyroSpeed = 1f)
        repeat(100) { index ->
            val result = guard.frame(
                start + 40_000_000L + index * 20_000_000L,
                180f, previous = 155f, publicHinge = 180f,
                offlineRaw = 160f, adaptiveAngle = 179f, matchMode = "strict",
                gyroSpeed = 1f,
            )
            assertTrue("Coarse 180 and disagreeing ML are no hinge evidence", result.held)
            assertTrue("Ambiguity must be visible", result.uncertain)
            assertEquals(155f, result.angle!!, 0f)
        }
    }

    @Test
    fun apparentUnfoldingUnderGyroMotionDoesNotOverrideTrueHeldAngle() {
        val guard = OrientationMotionGuard()
        val start = 10_000_000_000L
        guard.frame(start, 90f, magneticX = 100f)
        val first = guard.frame(start + 10_000_000L, 100f, 90f,
            magneticX = 102f, gyroSpeed = 0.8f)
        assertTrue(first.held)
        repeat(40) { i ->
            val estimate = 105f + i
            val output = guard.frame(
                start + 20_000_000L + i * 10_000_000L,
                estimate,
                previous = 90f,
                magneticX = 103f,
                gyroSpeed = 0.8f,
            )
            assertTrue("Only a changing ML prediction cannot prove unfolding", output.held)
            assertTrue(output.uncertain)
            assertEquals(90f, output.angle!!, 0f)
        }
        val realFold = guard.frame(
            start + 450_000_000L, 149f, previous = 90f,
            magneticX = 127f, publicHinge = 180f, gyroSpeed = 1f,
        )
        assertFalse("The actual hinge-state change must release immediately", realFold.held)
        assertTrue(realFold.hingeMotionEvidence)
        assertEquals(149f, realFold.angle!!, 0f)
    }

    @Test
    fun reproducedPureRotationWith180PublicAndWrong155MlpKeepsCorrectAngle() {
        val guard = OrientationMotionGuard()
        val start = 11_000_000_000L
        guard.frame(start, 177.4f, previous = 177.4f, publicHinge = 180f,
            magneticX = -43f, gyroSpeed = 0f)
        // Regression from 20 November: Samsung factory=177°, public=180°,
        // gyro reached 11.62 rad/s and the old guard displayed 155.8°.
        // No field jump above 7uT/sample was present during that rotation.
        repeat(375) { index ->
            val progress = index / 374f
            val candidate = 177.4f - 21.6f * kotlin.math.sin(
                Math.PI.toFloat() * progress,
            )
            val output = guard.frame(
                start + (index + 1) * 8_000_000L,
                candidate,
                previous = 177.4f,
                magneticX = -43f + 28f * progress,
                publicHinge = 180f,
                gyroSpeed = 1.2f + 10f * kotlin.math.sin(
                    Math.PI.toFloat() * progress,
                ),
            )
            assertTrue("Rotation sample $index cannot change the held hinge angle", output.held)
            assertEquals(177.4f, output.angle!!, 0.001f)
        }
        // One-second stationary interval, with an incorrect new-pose ML
        // prediction. The old timeout released the 155° value here.
        repeat(60) { index ->
            val output = guard.frame(
                start + 3_100_000_000L + index * 20_000_000L,
                156f, previous = 177.4f,
                magneticX = -15f, publicHinge = 180f,
                gyroSpeed = 0.002f,
            )
            assertTrue(output.held)
            assertEquals(177.4f, output.angle!!, 0.001f)
        }
    }

    @Test
    fun rotatingFieldVectorWithConstantMagnitudeIsNotFoldEvidence() {
        val guard = OrientationMotionGuard()
        val start = 12_000_000_000L
        guard.frame(start, 177f, 177f, publicHinge = 180f, magneticX = 100f)
        guard.frame(start + 8_000_000L, 163f, 177f, publicHinge = 180f,
            magneticX = 101f, gyroSpeed = 0.6f)
        // Rotating Earth's magnetic field can move the XYZ vector by >25uT
        // in one sample without changing its norm appreciably.
        val frame = guard.update(
            timestampNanos = start + 16_000_000L,
            magneticX = 125f, magneticY = 164f, magneticZ = -260f,
            gravityX = 0f, gravityY = 0f, gravityZ = 9.81f,
            gyroSpeedRadiansPerSecond = 0.6f,
            publicHinge = 180f, modelAngle = 156f, displayedAngle = 177f,
        )
        assertTrue(frame.held)
        assertEquals(177f, frame.angle!!, 0f)
        assertFalse(frame.hingeMotionEvidence)
    }

    @Test
    fun correlatedModelsAfterRotationCannotTurnReal155IntoFalse178() {
        val guard = OrientationMotionGuard()
        val start = 13_000_000_000L
        guard.frame(start, 155f, 155f, publicHinge = 180f, magneticX = 100f)
        guard.frame(start + 8_000_000L, 178f, 155f,
            publicHinge = 180f, magneticX = 101f, gyroSpeed = 1.2f)
        repeat(75) { index ->
            val output = guard.frame(
                start + 20_000_000L + index * 20_000_000L,
                178.4f, previous = 155f, publicHinge = 180f,
                magneticX = 101f, gyroSpeed = 0.001f,
                offlineRaw = 178f, adaptiveAngle = 178.4f, matchMode = "strict",
            )
            assertTrue(
                "Both trained models can agree on the wrong pose-dependent angle",
                output.held,
            )
            assertEquals(155f, output.angle!!, 0f)
        }
    }

    @Test
    fun gentleWholePhoneRotationIsHeldBeforeMlCanDriftSeveralDegrees() {
        val guard = OrientationMotionGuard()
        val start = 14_000_000_000L
        guard.frame(start, 90f, publicHinge = 90f)
        // Old threshold=0.08 rad/s ignored this ~1.7 deg/s turn,
        // exposing pose-dependent ML readings as if hinge had moved.
        val slowTurn = guard.frame(
            start + 20_000_000L, 93.8f, previous = 90f,
            gyroSpeed = 0.03f, publicHinge = 90f,
        )
        assertTrue(slowTurn.held)
        assertEquals(90f, slowTurn.angle!!, 0f)
        val next = guard.frame(
            start + 40_000_000L, 99.7f, previous = 90f,
            gyroSpeed = 0.03f, publicHinge = 90f,
        )
        assertTrue(next.held)
        assertEquals(90f, next.angle!!, 0f)
    }

    @Test
    fun stationaryGyroNoiseDoesNotFreezeValidPrediction() {
        val guard = OrientationMotionGuard()
        val start = 15_000_000_000L
        guard.frame(start, 90f, publicHinge = 90f)
        repeat(20) { index ->
            val result = guard.frame(
                start + (index + 1) * 20_000_000L,
                90.1f, previous = 90f,
                gyroSpeed = 0.01f, publicHinge = 90f,
            )
            assertFalse(result.held)
        }
    }
}
