package com.mentoster.flip7angle.sdk

/** Status is part of the measurement, not merely a UI hint. */
enum class HingeAngleStatus {
    /** A new model measurement, still experimental and orientation-sensitive. */
    LIVE_ESTIMATE,

    /** Last accepted angle held because the device body is rotating. */
    HELD_DURING_ROTATION,

    /** There is no complete gravity/magnetometer/public-hinge sample yet. */
    WAITING_FOR_SENSORS,
}

data class HingeAngleReading(
    /** Current best displayed estimate; null until the required sensors arrive. */
    val angleDegrees: Float?,
    /** Latest raw Boost prediction, even while the main result is held. */
    val rawModelDegrees: Float?,
    val status: HingeAngleStatus,
    /** Android's coarse state, NOT a precise Samsung hinge measurement. */
    val coarseHingeDegrees: Float?,
    /** Android sensor timestamp in nanoseconds (elapsed-realtime timebase). */
    val timestampNanos: Long,
    /** Human-readable heuristic diagnostic, never physical ground truth. */
    val reason: String,
    /** Model trained and validated on SM-F766B; on other devices, experimental. */
    val testedOnDevice: Boolean,
) {
    val held: Boolean get() = status == HingeAngleStatus.HELD_DURING_ROTATION
}

/** Called on the Android main thread by [Flip7HingeSensor]. */
fun interface HingeAngleListener {
    fun onAngle(reading: HingeAngleReading)
}
