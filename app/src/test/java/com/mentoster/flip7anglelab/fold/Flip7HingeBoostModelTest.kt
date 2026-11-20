package com.mentoster.flip7anglelab.fold

import java.io.ByteArrayInputStream
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Flip7HingeBoostModelTest {
    private fun loadModel(): Flip7HingeBoostModel =
        File("src/main/assets/${Flip7HingeBoostModel.ASSET_NAME}")
            .inputStream().use(Flip7HingeBoostModel::load)

    @Test
    fun packagedBoostModelMatchesPythonExport() {
        val model = loadModel()
        assertEquals(120, model.treeCount)

        // Deterministic exported-model parity fixtures, not accuracy claims.
        val opened = model.predict(
            17.88f, 192.42f, -299.46f,
            0.7339871f, -0.12546127f, -9.778304f, 180f,
        )
        val mid = model.predict(
            12.12f, 189.12f, -280.08f,
            -0.237781f, 3.7174f, -9.071612f, 90f,
        )
        val closed = model.predict(
            4140f, 240f, 1540f, 0f, 9.81f, 0f, 0f,
        )
        assertEquals(170.3832f, opened, 0.02f)
        assertEquals(150.9543f, mid, 0.02f)
        assertEquals(18.1873f, closed, 0.02f)
    }

    @Test
    fun estimatorPrefersBundledBoostOverOldMlpWithoutAdaptiveSamples() {
        val estimator = MagneticHingeEstimator().apply { usePretrainedModel(loadModel()) }
        estimator.updateGravity(
            990_000_000L,
            0.7339871f, -0.12546127f, -9.778304f,
        )
        val output = estimator.update(
            1_000_000_000L,
            17.88f, 192.42f, -299.46f, 180f,
        )
        assertEquals("Boost", estimator.lastPredictionSource)
        assertEquals(170.3832f, requireNotNull(estimator.lastRawPrediction), 0.02f)
        assertTrue(requireNotNull(output) in 165f..180f)
    }

    @Test
    fun missingStartupHingeAndGravityNeverSeedsWrongSvrAngle() {
        val estimator = MagneticHingeEstimator().apply { usePretrainedModel(loadModel()) }
        val startup = estimator.update(
            1_000_000_000L,
            17.88f, 192.42f, -299.46f,
            null,
        )
        assertEquals(null, startup)
        assertEquals(null, estimator.lastRawPrediction)
        assertEquals("waiting-for-sensors", estimator.lastPredictionSource)

        estimator.updateGravity(
            1_050_000_000L,
            0.7339871f, -0.12546127f, -9.778304f,
        )
        val complete = estimator.update(
            1_060_000_000L,
            17.88f, 192.42f, -299.46f, 180f,
        )
        assertEquals("Boost", estimator.lastPredictionSource)
        assertEquals(170.3832f, requireNotNull(estimator.lastRawPrediction), 0.02f)
        assertTrue("Startup must use Boost, not the ~31° SVR", requireNotNull(complete) >= 165f)
    }

    @Test
    fun corruptedModelsAndInvalidSensorReadingsAreRejected() {
        val model = loadModel()
        assertTrue(model.predict(Float.NaN, 1f, 2f, 0f, 9.81f, 0f, 90f).isNaN())
        assertTrue(model.predict(1f, 2f, 3f, 0f, 0f, 0f, 90f).isNaN())
        val failed = runCatching {
            ByteArrayInputStream("invalid-model".toByteArray()).use(Flip7HingeBoostModel::load)
        }
        assertTrue(failed.isFailure)
    }

    @Test
    fun retrainedModelMatchesSamsung177OnNewlyLearnedPose() {
        val boost = loadModel().predict(
            -43.38f, 98.04f, -110.10f,
            0.09f, 9.72f, 1.29f, 180f,
        )
        // This reference-labeled live pose was absent when v1 was trained,
        // causing a ~20-degree error in the former autonomous model.
        assertEquals(177.2131f, boost, 0.02f)
    }
}
