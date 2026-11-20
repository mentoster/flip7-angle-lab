package com.mentoster.flip7anglelab.fold

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MagneticHingeEstimatorTest {
    @Test
    fun capturedControlPoints_mapToExpectedRegions() {
        val estimator = MagneticHingeEstimator()

        val open = estimator.update(
            timestampNanos = 1_000_000_000L,
            xMicroTesla = 1f,
            yMicroTesla = 150f,
            zMicroTesla = -365f,
            publicHingeDegrees = 180f,
        )!!
        assertEquals(180f, open, 0f)

        estimator.reset()
        val middle = estimator.update(
            timestampNanos = 2_000_000_000L,
            xMicroTesla = 1.44f,
            yMicroTesla = 186.9f,
            zMicroTesla = -279.48f,
            publicHingeDegrees = 90f,
        )!!
        assertTrue("middle=$middle", middle in 75f..105f)

        estimator.reset()
        val closed = estimator.update(
            timestampNanos = 3_000_000_000L,
            xMicroTesla = 4140f,
            yMicroTesla = 240f,
            zMicroTesla = 1540f,
            publicHingeDegrees = 0f,
        )!!
        assertEquals(0f, closed, 0f)
    }

    @Test
    fun invalidMagneticSample_isRejected() {
        val estimator = MagneticHingeEstimator()
        val result = estimator.update(1L, Float.NaN, 0f, 0f, 90f)
        assertEquals(null, result)
    }

    @Test
    fun freshGravity_keepsClosedEndpointGuard() {
        val estimator = MagneticHingeEstimator()
        estimator.updateGravity(
            timestampNanos = 990_000_000L,
            x = 0f,
            y = 9.81f,
            z = 0f,
        )

        val closed = estimator.update(
            timestampNanos = 1_000_000_000L,
            xMicroTesla = 4140f,
            yMicroTesla = 240f,
            zMicroTesla = 1540f,
            publicHingeDegrees = 0f,
        )

        assertEquals(0f, requireNotNull(closed), 0f)
    }

    @Test
    fun calibrationV2Mlp_matchesTrainingReferencePoint() {
        val predicted = Flip7MagneticMlpModel.predict(
            x = 12.12f,
            y = 189.119995f,
            z = -280.079987f,
            gravityX = -0.237781f,
            gravityY = 3.7174f,
            gravityZ = -9.071612f,
            publicHinge = 90f,
        )

        assertEquals(100.45f, predicted, 0.2f)
    }

    @Test
    fun stableOpenEndpoint_isRaisedOnlyAfterDwell() {
        val estimator = MagneticHingeEstimator()
        val start = 10_000_000_000L

        fun sample(time: Long): Float {
            estimator.updateGravity(
                timestampNanos = time - 1_000_000L,
                x = 0.7339871f,
                y = -0.12546127f,
                z = -9.778304f,
            )
            return requireNotNull(
                estimator.update(
                    timestampNanos = time,
                    xMicroTesla = 17.88f,
                    yMicroTesla = 192.42f,
                    zMicroTesla = -299.46f,
                    publicHingeDegrees = 180f,
                ),
            )
        }

        val initial = sample(start)
        val beforeDwell = sample(start + 80_000_000L)
        val firstStage = sample(start + 120_000_000L)
        val secondStage = sample(start + 320_000_000L)

        assertTrue("initial=$initial", initial < 175f)
        assertTrue("beforeDwell=$beforeDwell", beforeDwell < 175f)
        assertTrue("firstStage=$firstStage", firstStage >= 175f)
        assertTrue("secondStage=$secondStage", secondStage >= 177f)
    }

    @Test
    fun openEndpointDwell_resetsWhenPublicHingeLeavesOpenState() {
        val estimator = MagneticHingeEstimator()
        val start = 20_000_000_000L

        fun sample(time: Long, publicHinge: Float): Float {
            estimator.updateGravity(
                timestampNanos = time - 1_000_000L,
                x = 0.7339871f,
                y = -0.12546127f,
                z = -9.778304f,
            )
            return requireNotNull(
                estimator.update(
                    timestampNanos = time,
                    xMicroTesla = 17.88f,
                    yMicroTesla = 192.42f,
                    zMicroTesla = -299.46f,
                    publicHingeDegrees = publicHinge,
                ),
            )
        }

        sample(start, 180f)
        sample(start + 80_000_000L, 180f)
        sample(start + 90_000_000L, 135f)
        val reopened = sample(start + 100_000_000L, 180f)

        assertTrue("reopened=$reopened", reopened < 175f)
    }
}
