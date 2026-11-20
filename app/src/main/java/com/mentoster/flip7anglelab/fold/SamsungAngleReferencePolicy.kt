package com.mentoster.flip7anglelab.fold

/**
 * Samsung's FoldingAngle is an on-change sensor, not a continuous heartbeat.
 *
 * Its last value remains current while the reader stays attached and receives
 * further folding events. Discarding it after 350 ms causes the UI to jump
 * back to an inaccurate magnetic ML estimate even on a stationary hinge.
 * We still reject a reference if its source disconnected or its coarse
 * posture contradicts the public sensor.
 */
internal object SamsungAngleReferencePolicy {
    fun trustedAngle(
        sample: SamsungAngleSample?,
        sourceActive: Boolean,
        publicHinge: Float?,
    ): Float? {
        val angle = sample?.angle?.takeIf { it.isFinite() && it in 0f..180f } ?: return null
        if (!sourceActive) return null
        return when {
            publicHinge == null || !publicHinge.isFinite() -> angle
            publicHinge >= 179f -> angle.takeIf { it >= 135f }
            publicHinge <= 1f -> angle.takeIf { it <= 45f }
            else -> angle
        }
    }
}
