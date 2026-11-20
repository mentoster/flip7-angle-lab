package com.mentoster.flip7angle.sdk

import com.mentoster.flip7anglelab.fold.AngleEstimateStabilizer
import com.mentoster.flip7anglelab.fold.OrientationMotionGuard

/** Hardware-independent sensor fusion, shared by production and JVM tests. */
internal class HingeAngleEngine(
    private val predictor: Flip7HingePredictor,
    private val testedOnDevice: Boolean,
) {
    private val rotationGuard = OrientationMotionGuard()
    private val stabilizer = AngleEstimateStabilizer()
    private var current: Float? = null
    private var lastTimestamp = 0L

    fun reset() {
        rotationGuard.reset()
        stabilizer.reset()
        current = null
        lastTimestamp = 0L
    }

    fun frame(
        timestampNanos: Long,
        magneticX: Float,
        magneticY: Float,
        magneticZ: Float,
        gravityX: Float,
        gravityY: Float,
        gravityZ: Float,
        gyroSpeed: Float?,
        coarseHingeDegrees: Float?,
    ): HingeAngleReading {
        if (timestampNanos <= lastTimestamp) {
            return HingeAngleReading(
                current, null, HingeAngleStatus.WAITING_FOR_SENSORS,
                coarseHingeDegrees, timestampNanos,
                "non-monotonic-timestamp", testedOnDevice,
            )
        }
        lastTimestamp = timestampNanos
        val coarse = coarseHingeDegrees?.takeIf(Float::isFinite)
        val raw = if (coarse != null) predictor.predict(
            magneticX, magneticY, magneticZ,
            gravityX, gravityY, gravityZ, coarse,
        ) else null
        if (raw == null) {
            return HingeAngleReading(
                null, null, HingeAngleStatus.WAITING_FOR_SENSORS,
                coarse, timestampNanos, "missing-or-invalid-sensors", testedOnDevice,
            )
        }
        val decision = rotationGuard.update(
            timestampNanos = timestampNanos,
            magneticX = magneticX,
            magneticY = magneticY,
            magneticZ = magneticZ,
            gravityX = gravityX,
            gravityY = gravityY,
            gravityZ = gravityZ,
            gyroSpeedRadiansPerSecond = gyroSpeed,
            publicHinge = coarse,
            modelAngle = raw,
            displayedAngle = current,
            offlineRawAngle = raw,
        )
        if (decision.held) {
            // Keep both the output and the internal filter frozen. Feeding
            // pose-contaminated values to the filter would cause a delayed
            // jump as soon as rotation is released.
            current = decision.angle ?: current
            return HingeAngleReading(
                current, raw, HingeAngleStatus.HELD_DURING_ROTATION,
                coarse, timestampNanos, decision.reason, testedOnDevice,
            )
        }
        if (decision.reanchored) stabilizer.reset()
        val instant = decision.hingeMotionEvidence ||
            decision.reason == "rotation-endpoint-consensus"
        val filtered = stabilizer.update(
            timestampNanos = timestampNanos,
            adaptiveEstimate = null,
            offlineEstimate = raw,
            publicHinge = coarse,
            immediateMotion = instant,
        )
        current = filtered.angle
        return HingeAngleReading(
            current, raw, HingeAngleStatus.LIVE_ESTIMATE,
            coarse, timestampNanos, decision.reason, testedOnDevice,
        )
    }
}
