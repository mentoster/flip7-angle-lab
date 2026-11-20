package com.mentoster.flip7anglelab.fold

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptiveAngleCalibrationTest {
    private fun sample(x: Float, angle: Float, public: Float = 180f) =
        AdaptiveAngleCalibration.Sample(
            magneticX = x, magneticY = 155f, magneticZ = -355f,
            gravityX = 0f, gravityY = 0f, gravityZ = -9.8f,
            publicHinge = public, exactAngle = angle,
        )

    @Test
    fun learnedOpenPosition_doesNotNeedLiveReferenceToPredict() {
        val model = AdaptiveAngleCalibration()
        assertNull(model.predict(sample(12f, 0f)))
        assertTrue(model.observe(sample(12f, 179f)))
        assertEquals(179f, requireNotNull(model.predict(sample(12.1f, 0f))), 0.2f)
    }

    @Test
    fun coarsePublic180_doesNotForceDistantSensorPositionTo180() {
        val model = AdaptiveAngleCalibration()
        model.observe(sample(12f, 179f))
        // Same public hinge state can also appear around 150°.
        assertNull(model.predict(sample(60f, 0f)))
        assertNull(model.predict(sample(12f, 0f, public = 90f)))
    }

    @Test
    fun separateLearnedAngles_areInterpolated() {
        val model = AdaptiveAngleCalibration()
        model.observe(sample(12f, 179f))
        model.observe(sample(27f, 160f))
        val estimate = requireNotNull(model.predict(sample(20f, 0f)))
        assertTrue("predicted=$estimate", estimate in 160f..179f)
    }

    @Test
    fun samplesPersistAndRejectInvalidValues() {
        val model = AdaptiveAngleCalibration(maxSamples = 2)
        assertTrue(model.observe(sample(12f, 179f)))
        assertTrue(!model.observe(sample(12.05f, 179f)))
        assertTrue(model.observe(sample(30f, 165f)))
        assertTrue(model.observe(sample(45f, 150f)))
        assertEquals(2, model.sampleCount)

        val restored = AdaptiveAngleCalibration()
        restored.restore(model.snapshot().mapNotNull { AdaptiveAngleCalibration.Sample.parse(it.serialize()) }.asSequence())
        assertEquals(2, restored.sampleCount)
        assertNull(AdaptiveAngleCalibration.Sample.parse("NaN,1,2,3,4,5,180,179"))
    }

    @Test
    fun repeatedStationarySamplesDoNotEvictOtherAnglesOrOrientations() {
        val cache = AdaptiveAngleCalibration(maxSamples = 40)
        fun example(x: Float, angle: Float, gx: Float, gz: Float) =
            sample(x, angle).copy(gravityX = gx, gravityZ = gz)

        // A 90° portrait calibration must never crowd out distinctive
        // 30°, 130° and upside-down 90° examples.
        cache.observe(example(30f, 30f, 0f, -9.8f))
        cache.observe(example(35f, 130f, 0f, -9.8f))
        cache.observe(example(35f, 90f, 0f, 9.8f))
        repeat(150) { index ->
            cache.observe(example(10f + index * .02f, 90f, 0f, -9.8f))
        }
        assertTrue(cache.sampleCount <= 15)
        assertTrue(cache.snapshot().any { it.exactAngle == 30f })
        assertTrue(cache.snapshot().any { it.exactAngle == 130f })
        assertTrue(cache.snapshot().any { it.exactAngle == 90f && it.gravityZ > 0f })
    }

    @Test
    fun fullCacheEvictsCrowdedPoseBeforeRareAngle() {
        val cache = AdaptiveAngleCalibration(maxSamples = 16)
        cache.observe(sample(200f, 40f))
        repeat(50) { cache.observe(sample(20f + it, 90f)) }
        cache.observe(sample(201f, 40f))
        assertTrue(cache.snapshot().any { it.exactAngle == 40f })
    }

    @Test
    fun restoringExistingLearningCacheDoesNotSilentlyDeleteExamples() {
        val model = AdaptiveAngleCalibration(maxSamples = 40)
        val recorded = (0..39).map { sample(11f + it, 90f) }
        model.restore(recorded.asSequence())
        assertEquals(40, model.sampleCount)
        assertEquals(recorded, model.snapshot())
    }

    @Test
    fun orientationTolerantSearchFindsConsistentNeighboringPose() {
        val cache = AdaptiveAngleCalibration()
        repeat(10) { index ->
            cache.observe(
                sample(10f + index * 7f, 90f)
                    .copy(gravityX = 0f, gravityZ = -9.8f),
            )
        }
        val rotated = sample(32f, 0f).copy(
            magneticY = 162f,
            gravityX = 4f,
            gravityZ = -8.8f,
        )
        val estimate = requireNotNull(cache.predict(rotated))
        assertEquals(90f, estimate, 0.1f)
        assertEquals("orientation-tolerant", cache.lastMatchMode)
    }

    @Test
    fun conflictingNeighborsInUnseenOrientationAreRejected() {
        val cache = AdaptiveAngleCalibration()
        repeat(5) { index ->
            cache.observe(sample(10f + index * 8f, 90f))
            cache.observe(sample(12f + index * 8f, 150f))
        }
        val query = sample(29f, 0f)
        assertNull(cache.predict(query))
        assertEquals("none", cache.lastMatchMode)
    }
}
