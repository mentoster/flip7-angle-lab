package com.mentoster.flip7anglelab.fold

import java.util.Locale
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Debug-build-only trace formatting and throttling for the full angle pipeline.
 *
 * Every magnetometer sample is observed to measure actual field steps. Log
 * lines are emitted on state transitions, significant jumps, every ~125 ms
 * during motion, and every second at rest. No personal identifiers, pairing
 * material, arbitrary logcat contents, or raw training rows are recorded.
 */
internal class AngleDiagnosticTrace {
    data class Vector(val x: Float, val y: Float, val z: Float) {
        val length: Float get() = sqrt(x * x + y * y + z * z)
        fun distance(other: Vector): Float {
            val dx = x - other.x
            val dy = y - other.y
            val dz = z - other.z
            return sqrt(dx * dx + dy * dy + dz * dz)
        }
    }

    data class Frame(
        val timestampNanos: Long,
        val magnet: Vector,
        val gravity: Vector?,
        val gyro: Vector?,
        val gravityAgeMs: Long?,
        val gyroAgeMs: Long?,
        val hingeAgeMs: Long?,
        val publicHinge: Float?,
        val reference: Float?,
        val referenceAgeMs: Long?,
        val referenceTrusted: Boolean = false,
        val boost: Float?,
        val offlineSmoothed: Float?,
        val adaptive: Float?,
        val matchMode: String,
        val guardReason: String,
        val guardAngle: Float?,
        val stabilized: Float?,
        val displayed: Float?,
        val held: Boolean,
        val rotationHeld: Boolean = false,
        val adaptiveHeld: Boolean = false,
        val uncertain: Boolean,
        val immediate: Boolean,
        val reanchored: Boolean,
        val coarseFallback: Boolean,
        val magnetometerHz: Float,
        val gyroscopeHz: Float,
        val learnedSamples: Int,
    )

    private var previousMagnet: Vector? = null
    private var previousTimestampNanos = 0L
    private var lastLogNanos = 0L
    private var lastReason: String? = null
    private var lastHinge: Float? = null
    private var lastDisplayed: Float? = null
    private var lastUncertain = false
    private var lastHeld = false

    fun offer(frame: Frame): String? {
        val timestamp = frame.timestampNanos
        if (timestamp <= 0L || (previousTimestampNanos > 0L && timestamp <= previousTimestampNanos)) {
            return null
        }
        val step = previousMagnet?.distance(frame.magnet)
        val dtMs = if (previousTimestampNanos > 0L) {
            (timestamp - previousTimestampNanos) / 1_000_000f
        } else null
        previousMagnet = frame.magnet
        previousTimestampNanos = timestamp

        val motion = (frame.gyro?.length ?: 0f) >= 0.08f ||
            frame.held || frame.uncertain
        val changedState = lastReason != frame.guardReason ||
            lastHinge != frame.publicHinge ||
            lastHeld != frame.held ||
            lastUncertain != frame.uncertain
        val jump = frame.displayed != null && lastDisplayed != null &&
            abs(frame.displayed - lastDisplayed!!) >= 5f
        val since = if (lastLogNanos == 0L) Long.MAX_VALUE else timestamp - lastLogNanos
        val interval = if (motion) MOVING_INTERVAL_NS else IDLE_INTERVAL_NS
        if (lastLogNanos != 0L &&
            since < if (changedState || jump) EVENT_MIN_INTERVAL_NS else interval
        ) return null

        lastLogNanos = timestamp
        lastReason = frame.guardReason
        lastHinge = frame.publicHinge
        lastHeld = frame.held
        lastUncertain = frame.uncertain
        lastDisplayed = frame.displayed
        val event = when {
            changedState -> "transition"
            jump -> "angle-jump"
            motion -> "motion-sample"
            else -> "idle-sample"
        }
        val gravityNorm = frame.gravity?.length?.takeIf { it >= 0.5f }
        val magneticProjection = if (frame.gravity != null && gravityNorm != null) {
            (frame.magnet.x * frame.gravity.x +
                frame.magnet.y * frame.gravity.y +
                frame.magnet.z * frame.gravity.z) / gravityNorm
        } else null
        val perpendicular = magneticProjection?.let {
            sqrt((frame.magnet.length * frame.magnet.length - it * it).coerceAtLeast(0f))
        }
        val disagreement = if (frame.boost != null && frame.adaptive != null) {
            abs(frame.boost - frame.adaptive)
        } else null
        val referenceUsable = frame.reference != null && frame.referenceTrusted
        val refError = if (referenceUsable && frame.displayed != null) {
            frame.displayed - frame.reference
        } else null

        return buildString(520) {
            append("event=").append(event)
            append(" tNs=").append(timestamp)
            append(" dtMs=").append(dtMs.fmt())
            append(" reason=").append(frame.guardReason)
            append(" public=").append(frame.publicHinge.fmt())
            append(" hingeAgeMs=").append(frame.hingeAgeMs ?: "null")
            append(" samsung=").append(frame.reference.fmt())
            append(" samsungAgeMs=").append(frame.referenceAgeMs ?: "null")
            append(" mag=").append(frame.magnet.fmt())
            append(" magNorm=").append(frame.magnet.length.fmt())
            append(" magStep=").append(step.fmt())
            append(" magGravityProjection=").append(magneticProjection.fmt())
            append(" magPerpendicular=").append(perpendicular.fmt())
            append(" grav=").append(frame.gravity?.fmt() ?: "null")
            append(" gravAgeMs=").append(frame.gravityAgeMs ?: "null")
            append(" gyro=").append(frame.gyro?.fmt() ?: "null")
            append(" gyroSpeed=").append(frame.gyro?.length.fmt())
            append(" gyroAgeMs=").append(frame.gyroAgeMs ?: "null")
            append(" boostRaw=").append(frame.boost.fmt())
            append(" offline=").append(frame.offlineSmoothed.fmt())
            append(" knn=").append(frame.adaptive.fmt())
            append(" modelDisagreement=").append(disagreement.fmt())
            append(" match=").append(frame.matchMode)
            append(" gate=").append(frame.guardAngle.fmt())
            append(" stabilized=").append(frame.stabilized.fmt())
            append(" display=").append(frame.displayed.fmt())
            append(" displaySource=").append(if (referenceUsable) "samsung-on-change" else "ml")
            append(" referenceError=").append(refError.fmt())
            append(" held=").append(frame.held)
            append(" rotationHeld=").append(frame.rotationHeld)
            append(" adaptiveHeld=").append(frame.adaptiveHeld)
            append(" uncertain=").append(frame.uncertain)
            append(" immediate=").append(frame.immediate)
            append(" reanchor=").append(frame.reanchored)
            append(" coarse=").append(frame.coarseFallback)
            append(" magHz=").append(frame.magnetometerHz.fmt())
            append(" gyroHz=").append(frame.gyroscopeHz.fmt())
            append(" learned=").append(frame.learnedSamples)
        }
    }

    private fun Vector.fmt(): String = "(${x.fmt()},${y.fmt()},${z.fmt()})"
    private fun Float?.fmt(): String = if (this == null || !isFinite()) {
        "null"
    } else String.format(Locale.US, "%.2f", this)

    private companion object {
        const val EVENT_MIN_INTERVAL_NS = 40_000_000L
        const val MOVING_INTERVAL_NS = 125_000_000L
        const val IDLE_INTERVAL_NS = 1_000_000_000L
    }
}
