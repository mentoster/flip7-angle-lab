package com.mentoster.flip7anglelab.fold

import kotlin.math.abs
import kotlin.math.max

/**
 * Stabilizes a model prediction, never a Samsung reference measurement.
 *
 * - A 3-sample median rejects isolated kNN neighbor-switching spikes.
 * - The sub-degree deadband keeps a stationary hinge from visibly vibrating.
 * - Time-based smoothing responds quickly to intentional folding.
 * - A brief adaptive-source grace period avoids jumping to an inaccurate
 *   offline MLP on a single missing nearest-neighbor match.
 */
internal class AngleEstimateStabilizer {
    data class Result(
        val angle: Float?,
        val adaptive: Boolean,
        val held: Boolean = false,
        val coarseFallback: Boolean = false,
    )

    private val targets = ArrayDeque<Float>(3)
    private var filteredAngle: Float? = null
    private var lastTimestampNanos = 0L
    private var lastAdaptiveAngle: Float? = null
    private var lastAdaptiveTimestampNanos = 0L
    private var lastAdaptiveHinge: Float? = null
    private var openCandidateSinceNanos = 0L
    private var openSnapped = false
    private var openExitSinceNanos = 0L

    fun reset() {
        targets.clear()
        filteredAngle = null
        lastTimestampNanos = 0L
        lastAdaptiveAngle = null
        lastAdaptiveTimestampNanos = 0L
        lastAdaptiveHinge = null
        openCandidateSinceNanos = 0L
        openSnapped = false
        openExitSinceNanos = 0L
    }

    fun update(
        timestampNanos: Long,
        adaptiveEstimate: Float?,
        offlineEstimate: Float?,
        publicHinge: Float?,
        hasLearnedSamples: Boolean = false,
        immediateMotion: Boolean = false,
    ): Result {
        if (lastTimestampNanos > 0L && timestampNanos <= lastTimestampNanos) {
            return Result(filteredAngle, adaptive = lastAdaptiveRecentlySeen(timestampNanos, publicHinge))
        }
        val adaptive = adaptiveEstimate?.takeIf(Float::isFinite)?.coerceIn(0f, 180f)
        if (adaptive != null) {
            lastAdaptiveAngle = adaptive
            lastAdaptiveTimestampNanos = timestampNanos
            lastAdaptiveHinge = publicHinge
        }
        // Never hold a learned angle indefinitely when the model no longer
        // matches the current pose. That previously froze the hinge display
        // for entire unfolding sequences. Use only a short dropout grace;
        // the pretrained Boost model is the continuous offline fallback.
        val heldAdaptive = if (!immediateMotion && adaptive == null &&
            lastAdaptiveRecentlySeen(timestampNanos, publicHinge)
        ) {
            lastAdaptiveAngle
        } else null
        val held = adaptive == null && heldAdaptive != null
        val useAdaptive = adaptive != null || heldAdaptive != null
        val adaptiveEvidence = adaptive ?: heldAdaptive
        // A coarse Android 180° event also occurs at real 150–165° angles.
        // Only a sustained near-open ADAPTIVE prediction justifies snapping
        // the displayed estimate to the physical end stop.
        val nearOpen = publicHinge != null && publicHinge >= 179f &&
            adaptiveEvidence != null &&
            adaptiveEvidence >= if (openSnapped) OPEN_RELEASE_DEGREES else OPEN_ENTER_DEGREES
        if (openSnapped && !nearOpen) {
            // Near Samsung's mechanical stop, kNN can rapidly alternate
            // between ~173.7° and ~177° while the hinge is STILL. Releasing
            // the 180° snap on one borderline neighbor switch produces a
            // visible 180↔174° oscillation. A confirmed hinge transition
            // (or a large change below 165°) is exempt from this guard.
            val verifiedDeparture = immediateMotion ||
                publicHinge == null || publicHinge < 179f ||
                (adaptiveEvidence != null && adaptiveEvidence <= CLEARLY_MOVING_AWAY_DEGREES)
            if (verifiedDeparture) {
                openSnapped = false
                openExitSinceNanos = 0L
            } else {
                if (openExitSinceNanos == 0L) openExitSinceNanos = timestampNanos
                if (timestampNanos - openExitSinceNanos >= OPEN_EXIT_CONFIRM_NANOS) {
                    openSnapped = false
                    openExitSinceNanos = 0L
                }
            }
        } else if (nearOpen) {
            openExitSinceNanos = 0L
        }
        if (!nearOpen && !openSnapped) {
            openCandidateSinceNanos = 0L
        } else if (nearOpen && !openSnapped) {
            if (openCandidateSinceNanos == 0L) openCandidateSinceNanos = timestampNanos
            if (timestampNanos - openCandidateSinceNanos >= OPEN_DWELL_NANOS) openSnapped = true
        }
        if (openSnapped) {
            // Override the small deadband only at a confirmed endpoint.
            targets.clear()
            targets.addLast(180f)
            filteredAngle = 180f
            lastTimestampNanos = timestampNanos
            return Result(180f, adaptive = true, held = held)
        }
        // Always use a live offline Boost prediction when available, even if
        // personal calibration exists but its pose is unmatched. A coarse
        // posture is only a last resort when *both* trained models are absent.
        val coarseFallback = adaptive == null && heldAdaptive == null &&
            offlineEstimate?.isFinite() != true &&
            publicHinge?.isFinite() == true && !immediateMotion
        val fallback = if (coarseFallback) when {
            publicHinge < 45f -> 15f
            publicHinge < 135f -> 90f
            else -> 165f
        } else offlineEstimate
        val target = (adaptive ?: heldAdaptive ?: fallback?.takeIf(Float::isFinite))
            ?.coerceIn(0f, 180f) ?: return Result(filteredAngle, useAdaptive)

        // An actual hinge transition is handled on this very sensor sample.
        // No 3-sample median, EMA, slew cap or 250 ms open-end dwell.
        // The rotation guard is responsible for authorizing this path.
        if (immediateMotion) {
            val current = if (publicHinge != null && publicHinge >= 179f &&
                adaptive != null && adaptive >= OPEN_ENTER_DEGREES
            ) 180f else target
            targets.clear()
            targets.addLast(current)
            filteredAngle = current
            lastTimestampNanos = timestampNanos
            openSnapped = current == 180f && adaptive != null
            openCandidateSinceNanos = 0L
            openExitSinceNanos = 0L
            return Result(current, useAdaptive, coarseFallback = coarseFallback)
        }

        if (targets.size == 3) targets.removeFirst()
        targets.addLast(target)
        val median = if (targets.size == 3) targets.sorted()[1] else target

        val old = filteredAngle
        val result = if (old == null || lastTimestampNanos == 0L) {
            median
        } else {
            val delta = median - old
            if (abs(delta) <= DEADBAND_DEGREES) {
                old
            } else {
                // Use sensor timestamps, not UI frames. Clamp long gaps (e.g. resume)
                // to avoid sudden jumps after an idle period.
                val dt = ((timestampNanos - lastTimestampNanos) / 1e9f).coerceIn(0.001f, 0.05f)
                val tau = if (abs(delta) > MOVING_THRESHOLD_DEGREES) {
                    MOVING_TIME_CONSTANT_SECONDS
                } else {
                    STATIONARY_TIME_CONSTANT_SECONDS
                }
                val alpha = dt / (tau + dt)
                val change = (delta * alpha).coerceIn(-max(dt * MAX_DEGREES_PER_SECOND, 0.25f),
                    max(dt * MAX_DEGREES_PER_SECOND, 0.25f))
                (old + change).coerceIn(0f, 180f)
            }
        }
        filteredAngle = result
        lastTimestampNanos = timestampNanos
        return Result(result, useAdaptive, held = held, coarseFallback = coarseFallback)
    }

    private fun lastAdaptiveRecentlySeen(timestampNanos: Long, publicHinge: Float?): Boolean =
        lastAdaptiveAngle != null &&
            lastAdaptiveTimestampNanos > 0L &&
            timestampNanos - lastAdaptiveTimestampNanos in 0L..ADAPTIVE_GRACE_NANOS &&
            lastAdaptiveHinge == publicHinge

    private companion object {
        const val OPEN_ENTER_DEGREES = 177.5f
        const val OPEN_RELEASE_DEGREES = 174f
        const val CLEARLY_MOVING_AWAY_DEGREES = 165f
        const val OPEN_EXIT_CONFIRM_NANOS = 100_000_000L
        const val OPEN_DWELL_NANOS = 250_000_000L
        const val ADAPTIVE_GRACE_NANOS = 40_000_000L
        const val DEADBAND_DEGREES = 0.75f
        const val MOVING_THRESHOLD_DEGREES = 5f
        const val MOVING_TIME_CONSTANT_SECONDS = 0.045f
        const val STATIONARY_TIME_CONSTANT_SECONDS = 0.17f
        const val MAX_DEGREES_PER_SECOND = 900f
    }
}
