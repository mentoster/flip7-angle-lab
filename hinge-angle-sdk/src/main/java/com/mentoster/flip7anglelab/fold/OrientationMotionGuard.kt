package com.mentoster.flip7anglelab.fold

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.sqrt

/**
 * Rejects spurious fold-angle changes caused by rotating the entire phone.
 *
 * The public Flip7 app has only the main IMU; Samsung protects the second
 * half's accelerometer behind SSENSOR. Consequently, an arbitrary whole-phone
 * rotation is physically indistinguishable from some hinge movements based on
 * a single magnetometer snapshot. We must expose that ambiguity by HOLDING
 * the last angle instead of presenting an unstable raw model guess as truth.
 *
 * A newly observed, independent hinge transition can resume prediction.
 * A phone rotation is not a hinge measurement; never discard a valid held
 * angle simply because a timer expires. No privileged angle or Samsung
 * READ_LOGS access is used by this guard.
 */
class OrientationMotionGuard {
    data class Output(
        val angle: Float?,
        val held: Boolean,
        /** Clear stale temporal smoothing only when confidently reacquiring. */
        val reanchored: Boolean = false,
        /** Strong hinge evidence: output this sensor reading without smoothing. */
        val immediate: Boolean = false,
        /** Coarse hinge transition or strong local magnet evidence (heuristic). */
        val hingeMotionEvidence: Boolean = false,
        /** Rotation may contaminate the current live ML prediction. */
        val uncertain: Boolean = false,
        /** Stable diagnostic reason; never interpret this as ground truth. */
        val reason: String = "tracking",
    )

    private data class Vector3(val x: Float, val y: Float, val z: Float) {
        val norm: Float get() = sqrt(x * x + y * y + z * z)

        fun distance(other: Vector3): Float {
            val dx = x - other.x
            val dy = y - other.y
            val dz = z - other.z
            return sqrt(dx * dx + dy * dy + dz * dz)
        }

        fun angleTo(other: Vector3): Float {
            val norms = norm * other.norm
            if (norms < 0.1f) return 0f
            val dot = (x * other.x + y * other.y + z * other.z) / norms
            return Math.toDegrees(
                acos(dot.coerceIn(-1f, 1f).toDouble()),
            ).toFloat()
        }
    }

    private var referenceGravity: Vector3? = null
    private var lastQuietGravity: Vector3? = null
    private var lastTrustedAngle: Float? = null
    private var referenceAngle: Float? = null
    private var heldAngle: Float? = null
    private var publicState: Float? = null
    private var lastTimestampNanos = 0L
    private var hingeTransitionUntilNanos = 0L
    private var previousMagnetic: Vector3? = null
    private var previousMagneticNanos = 0L
    private var agreementSinceNanos = 0L

    fun reset() {
        referenceGravity = null
        lastQuietGravity = null
        lastTrustedAngle = null
        referenceAngle = null
        heldAngle = null
        publicState = null
        lastTimestampNanos = 0L
        hingeTransitionUntilNanos = 0L
        previousMagnetic = null
        previousMagneticNanos = 0L
        agreementSinceNanos = 0L
    }

    fun update(
        timestampNanos: Long,
        magneticX: Float,
        magneticY: Float,
        magneticZ: Float,
        gravityX: Float,
        gravityY: Float,
        gravityZ: Float,
        gyroSpeedRadiansPerSecond: Float?,
        publicHinge: Float?,
        modelAngle: Float?,
        displayedAngle: Float?,
        offlineRawAngle: Float? = null,
        adaptiveAngle: Float? = null,
        adaptiveMatchMode: String = "none",
    ): Output {
        val candidate = modelAngle?.takeIf(Float::isFinite)?.coerceIn(0f, 180f)
        if (candidate == null) return Output(
            heldAngle ?: displayedAngle, heldAngle != null, reason = "missing-ml",
        )
        val inputs = listOf(
            magneticX, magneticY, magneticZ, gravityX, gravityY, gravityZ,
        )
        if (!inputs.all(Float::isFinite) || timestampNanos <= lastTimestampNanos) {
            return Output(heldAngle ?: candidate, heldAngle != null, reason = "invalid-or-stale-event")
        }

        val magnetic = Vector3(magneticX, magneticY, magneticZ)
        val gravity = Vector3(gravityX, gravityY, gravityZ)
        if (gravity.norm < 0.5f) return Output(
            heldAngle ?: candidate, heldAngle != null, reason = "invalid-gravity",
        )

        // Only a physical hinge transition changes Android's coarse state.
        // A whole-device rotation leaves the coarse posture unchanged.
        val postureChanged = publicState != null && publicHinge != null &&
            publicState != publicHinge
        if (postureChanged) {
            reset()
            // A coarse hinge-state transition is independent evidence that
            // the HINGE really moved. For a short interval prioritize live
            // inference over the gyro guard: folding while holding the phone
            // otherwise froze the old value for >1 second.
            hingeTransitionUntilNanos = timestampNanos + HINGE_TRANSITION_WINDOW_NANOS
        }
        publicState = publicHinge
        // Most magnetic changes from rotating the entire phone are gradual.
        // A rapid, substantial LOCAL field change can suggest a moving
        // hinge even before Android's coarse posture state changes. This
        // remains a heuristic, not an independent physical measurement.
        val dtSeconds = if (previousMagneticNanos > 0L) {
            (timestampNanos - previousMagneticNanos) / 1e9f
        } else 0f
        val fieldStep = previousMagnetic?.distance(magnetic) ?: 0f
        val normStep = previousMagnetic?.let { abs(it.norm - magnetic.norm) } ?: 0f
        previousMagnetic = magnetic
        previousMagneticNanos = timestampNanos
        lastTimestampNanos = timestampNanos

        if (referenceGravity == null) {
            referenceGravity = gravity
            lastQuietGravity = gravity
            lastTrustedAngle = candidate
            referenceAngle = candidate
            return Output(
                candidate, held = false, reanchored = postureChanged,
                immediate = postureChanged,
                hingeMotionEvidence = postureChanged,
                reason = if (postureChanged) "hinge-transition" else "initialize",
            )
        }

        if (timestampNanos <= hingeTransitionUntilNanos) {
            referenceGravity = gravity
            lastQuietGravity = gravity
            lastTrustedAngle = candidate
            referenceAngle = candidate
            return Output(
                candidate, held = false, immediate = true,
                hingeMotionEvidence = true, reason = "hinge-transition-window",
            )
        }

        val gyroMotion = gyroSpeedRadiansPerSecond
            ?.takeIf(Float::isFinite)
            ?.let { it >= GYRO_MOTION_RADIANS_PER_SECOND } == true
        val gyroSpeed = gyroSpeedRadiansPerSecond?.takeIf(Float::isFinite)?.coerceAtLeast(0f) ?: 0f
        val gravityMovedSinceLast = lastQuietGravity?.angleTo(gravity)?.let {
            it >= QUIET_GRAVITY_DRIFT_DEGREES
        } ?: false
        val fieldChangeSuggestsFolding = dtSeconds in 0.001f..0.1f &&
            fieldStep >= maxOf(
                MIN_FOLD_MAGNETIC_STEP_MICROTESLA,
                4f + gyroSpeed * dtSeconds * MAX_EXTERNAL_MAGNETIC_FIELD_MICROTESLA,
            ) &&
            normStep >= MIN_FOLD_MAGNETIC_NORM_STEP_MICROTESLA &&
            abs(candidate - (displayedAngle ?: lastTrustedAngle ?: candidate)) >=
                MIN_FOLD_ANGLE_STEP_DEGREES
        if (fieldChangeSuggestsFolding) {
            heldAngle = null
            referenceGravity = gravity
            lastQuietGravity = gravity
            lastTrustedAngle = candidate
            referenceAngle = candidate
            agreementSinceNanos = 0L
            return Output(
                candidate, held = false, reanchored = true, immediate = true,
                hingeMotionEvidence = true,
                reason = "magnetic-step-fold",
            )
        }
        val poseDrift = referenceGravity!!.angleTo(gravity)
        val lastAngle = heldAngle ?: displayedAngle ?: lastTrustedAngle ?: candidate
        val rotatingSlowly = poseDrift >= SLOW_TILT_DEGREES &&
            abs(candidate - (referenceAngle ?: lastAngle)) >= UNEXPLAINED_ANGLE_CHANGE_DEGREES

        if (heldAngle == null && (gyroMotion || rotatingSlowly)) {
            heldAngle = (displayedAngle ?: lastTrustedAngle ?: candidate).coerceIn(0f, 180f)
            lastQuietGravity = gravity
            agreementSinceNanos = 0L
            return Output(heldAngle, held = true, uncertain = true, reason = "rotation-hold-start")
        }

        if (heldAngle != null) {
            // There is no time-based release. The old 40 ms timeout was the
            // direct cause of a false 178° -> 156° jump during a 3.35 s
            // whole-phone rotation. Never trust a fresh pose-dependent ML
            // reading merely because the phone has been moving for a while.
            val gravityMoved = gravityMovedSinceLast
            if (gyroMotion || gravityMoved) {
                lastQuietGravity = gravity
                agreementSinceNanos = 0L
                return Output(heldAngle, held = true, uncertain = true, reason = "rotation-hold-motion")
            }

            // Once motion ends, only sustained convergence is safe:
            // a single kNN nearest-neighbor switch may momentarily cross
            // the old held angle before returning to the wrong new pose.
            val converged = abs(candidate - heldAngle!!) <= MATCH_HELD_ANGLE_DEGREES
            if (converged) {
                if (agreementSinceNanos == 0L) agreementSinceNanos = timestampNanos
                if (timestampNanos - agreementSinceNanos >= QUIET_REANCHOR_NANOS) {
                    reset()
                    publicState = publicHinge
                    lastTimestampNanos = timestampNanos
                    referenceGravity = gravity
                    lastQuietGravity = gravity
                    lastTrustedAngle = candidate
                    referenceAngle = candidate
                    return Output(
                        candidate, held = false, reanchored = true,
                        reason = "rotation-model-converged",
                    )
                }
            } else {
                agreementSinceNanos = 0L
            }

            // A previous version could hold an early startup guess (31°)
            // indefinitely while both trained models correctly predicted
            // ~178–179° and Android reported a fully open posture. Reacquire
            // after sustained agreement between the independent Boost and
            // nearest-neighbor predictions, never from coarse posture alone.
            // Do not jump to a different orientation's prediction immediately
            // after motion stops. Wait for a stable *local* magnetic change,
            // indicating a new hinge movement while the base half is still.
            val boost = offlineRawAngle?.takeIf(Float::isFinite)
            val neighbor = adaptiveAngle?.takeIf(Float::isFinite)
            val confirmingModels = boost != null && neighbor != null &&
                adaptiveMatchMode == "strict" &&
                abs(boost - neighbor) <= MODEL_AGREEMENT_DEGREES
            // In Android's coarse 180° state a true hinge can still be
            // around 155°. Two correlated ML models agreeing near 178°
            // after a rotation must NOT override a plausible 155° anchor.
            // Only repair the impossible early-startup guess (~31°).
            val clearlyWrongStartupAnchor = when {
                publicHinge != null && publicHinge >= 179f -> heldAngle!! < 120f
                publicHinge != null && publicHinge <= 1f -> heldAngle!! > 60f
                else -> false
            }
            val corroboratedEndpoint = confirmingModels && clearlyWrongStartupAnchor && when {
                publicHinge != null && publicHinge >= 179f ->
                    boost >= 176f && neighbor >= 176f
                publicHinge != null && publicHinge <= 1f ->
                    boost <= 4f && neighbor <= 4f
                else -> false
            }
            if (corroboratedEndpoint) {
                val recoveredAngle = candidate
                reset()
                publicState = publicHinge
                lastTimestampNanos = timestampNanos
                referenceGravity = gravity
                lastQuietGravity = gravity
                lastTrustedAngle = recoveredAngle
                referenceAngle = recoveredAngle
                return Output(
                    recoveredAngle, held = false, reanchored = true,
                    immediate = true, reason = "rotation-endpoint-consensus",
                )
            }
            // Never use a large accumulated XYZ distance since the end of
            // a rotation as standalone hinge proof: Earth's field can
            // change by tens of microtesla after yawing the whole phone.
            return Output(
                heldAngle, held = true, uncertain = true, reason = "rotation-hold-unverified",
            )
        }

        // Keep a cumulative angle baseline while gravity drifts; comparing
        // only consecutive estimates lets gradual pose changes escape.
        // When the base half is still in the original pose, a true hinge
        // movement can update the baseline without being mistaken for tilt.
        if (poseDrift <= 2f) {
            referenceAngle = candidate
        }
        lastTrustedAngle = candidate
        return Output(candidate, held = false)
    }

    private companion object {
        const val QUIET_REANCHOR_NANOS = 160_000_000L
        // On the SM-F766B the gyro at rest is ~0.002-0.01 rad/s. The
        // previous 0.08 rad/s missed gentle deliberate whole-phone turns
        // and let the pose-dependent predictor drift before the hold began.
        const val GYRO_MOTION_RADIANS_PER_SECOND = 0.025f
        const val SLOW_TILT_DEGREES = 1.5f
        const val UNEXPLAINED_ANGLE_CHANGE_DEGREES = 1.5f
        // A 1°/sample slow tilt must not repeatedly release/re-hold at
        // successively wrong angles. Gravity direction is fairly quiet at
        // rest, so detect cumulative pose movement at a tighter threshold.
        const val QUIET_GRAVITY_DRIFT_DEGREES = 0.4f
        const val MATCH_HELD_ANGLE_DEGREES = 2.5f
        const val HINGE_TRANSITION_WINDOW_NANOS = 350_000_000L
        const val MIN_FOLD_MAGNETIC_STEP_MICROTESLA = 7f
        const val MIN_FOLD_MAGNETIC_NORM_STEP_MICROTESLA = 6f
        const val MAX_EXTERNAL_MAGNETIC_FIELD_MICROTESLA = 110f
        const val MIN_FOLD_ANGLE_STEP_DEGREES = 3f
        const val MODEL_AGREEMENT_DEGREES = 5f
    }
}
