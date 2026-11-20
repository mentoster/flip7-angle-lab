package com.mentoster.flip7anglelab.fold

import kotlin.math.abs

enum class CalibrationOrientation(
    val id: String,
    val title: String,
    val instruction: String,
) {
    PORTRAIT(
        "portrait",
        "Portrait",
        "Hold the phone normally, with the main screen facing you and the hinge horizontal.",
    ),
    PORTRAIT_INVERTED(
        "portrait-inverted",
        "Upside-down portrait",
        "Rotate the fully opened phone upside down, keeping the main screen facing you.",
    ),
    LANDSCAPE_LEFT(
        "landscape-left",
        "Landscape left",
        "Rotate the fully opened phone 90° to the left, screen facing you.",
    ),
    LANDSCAPE_RIGHT(
        "landscape-right",
        "Landscape right",
        "Rotate the fully opened phone 90° to the right, screen facing you.",
    ),
    FACE_UP(
        "face-up",
        "Face up",
        "Hold the unfolded phone parallel to the floor, with both screens facing up.",
    ),
    FACE_DOWN(
        "face-down",
        "Face down",
        "Hold the unfolded phone parallel to the floor, with both screens facing down.",
    ),
}

enum class CalibrationSpeed(
    val id: String,
    val title: String,
    val instruction: String,
    val minLegSeconds: Float,
    val maxLegSeconds: Float,
) {
    SLOW("slow", "Slow", "Take about 4–7 seconds for each closing and opening motion.", 3.5f, 8.0f),
    NORMAL("normal", "Normal", "Fold and unfold at your normal everyday speed.", 1.2f, 3.8f),
    FAST("fast", "Fast", "Fold and unfold quickly but smoothly, without slamming the hinge.", 0.35f, 1.6f),
}

enum class CalibrationPhase {
    IDLE,
    WAIT_FOR_OPEN,
    CLOSE,
    OPEN,
    STATIC_HOLD,
    COMPLETE,
}

data class CalibrationStage(
    val orientation: CalibrationOrientation,
    val speed: CalibrationSpeed,
)

data class CalibrationV2State(
    val running: Boolean = false,
    val phase: CalibrationPhase = CalibrationPhase.IDLE,
    val stageIndex: Int = 0,
    val stageCount: Int = 0,
    val cycleInStage: Int = 0,
    val cyclesPerStage: Int = 0,
    val completedCycles: Int = 0,
    val totalCycles: Int = 0,
    val staticIndex: Int = 0,
    val staticCount: Int = 0,
    val staticTargetDegrees: Int? = null,
    val holdProgress: Float = 0f,
    val exactAngle: Float? = null,
    val instruction: String = "Ready to calibrate",
    val feedback: String = "",
    val recordingTag: String = "cal-v2|idle",
) {
    val complete: Boolean get() = phase == CalibrationPhase.COMPLETE
}

/**
 * Pure state machine for the guided master calibration. It deliberately consumes only
 * Samsung exact-angle samples, making the UI logic deterministic and unit-testable.
 */
class CalibrationV2Guide(
    private val cyclesPerStage: Int = 4,
    private val holdNanos: Long = 1_250_000_000L,
) {
    private val stages = CalibrationOrientation.entries.flatMap { orientation ->
        CalibrationSpeed.entries.map { speed -> CalibrationStage(orientation, speed) }
    }
    private val staticTargets = buildList {
        for (angle in 180 downTo 0 step 10) add(angle)
        for (angle in 10..180 step 10) add(angle)
    }

    var state: CalibrationV2State = CalibrationV2State(
        stageCount = stages.size,
        cyclesPerStage = cyclesPerStage,
        totalCycles = stages.size * cyclesPerStage,
        staticCount = staticTargets.size,
    )
        private set

    private var legStartedNanos: Long? = null
    private var holdStartedNanos: Long? = null

    fun start(initialAngle: Float?, timestampNanos: Long): CalibrationV2State {
        legStartedNanos = null
        holdStartedNanos = null
        state = CalibrationV2State(
            running = true,
            phase = CalibrationPhase.WAIT_FOR_OPEN,
            stageIndex = 0,
            stageCount = stages.size,
            cycleInStage = 0,
            cyclesPerStage = cyclesPerStage,
            completedCycles = 0,
            totalCycles = stages.size * cyclesPerStage,
            staticIndex = 0,
            staticCount = staticTargets.size,
            exactAngle = initialAngle,
            instruction = stagePreparationText(stages.first()),
        )
        if (initialAngle != null) state = state.copy(exactAngle = initialAngle.coerceIn(0f, 180f))
        return withTag(state)
    }

    fun confirmReady(): CalibrationV2State {
        if (!state.running || state.phase != CalibrationPhase.WAIT_FOR_OPEN) return state
        val angle = state.exactAngle
        val stage = stages[state.stageIndex]
        if (angle == null || angle < OPEN_READY_DEGREES) {
            state = state.copy(
                instruction = stagePreparationText(stage),
                feedback = "Open the phone to at least ${OPEN_READY_DEGREES.toInt()}° first",
            )
            return withTag(state)
        }
        legStartedNanos = null
        state = state.copy(
            phase = CalibrationPhase.CLOSE,
            instruction = "CLOSE the phone fully. ${stage.speed.instruction}",
            feedback = "Cycle started · no further taps needed",
        )
        return withTag(state)
    }

    fun stop(): CalibrationV2State {
        legStartedNanos = null
        holdStartedNanos = null
        state = state.copy(
            running = false,
            phase = CalibrationPhase.IDLE,
            instruction = "Calibration stopped",
            holdProgress = 0f,
        )
        return withTag(state)
    }

    fun update(angleDegrees: Float, timestampNanos: Long): CalibrationV2State {
        if (!state.running || !angleDegrees.isFinite()) return state
        val angle = angleDegrees.coerceIn(0f, 180f)
        state = state.copy(exactAngle = angle)

        when (state.phase) {
            CalibrationPhase.WAIT_FOR_OPEN -> updateWaitForOpen(angle)
            CalibrationPhase.CLOSE -> updateClose(angle, timestampNanos)
            CalibrationPhase.OPEN -> updateOpen(angle, timestampNanos)
            CalibrationPhase.STATIC_HOLD -> updateStatic(angle, timestampNanos)
            CalibrationPhase.COMPLETE, CalibrationPhase.IDLE -> Unit
        }
        state = withTag(state)
        return state
    }

    private fun updateWaitForOpen(angle: Float) {
        val stage = stages[state.stageIndex]
        state = state.copy(
            instruction = stagePreparationText(stage),
            feedback = if (angle >= OPEN_READY_DEGREES) {
                "Position ready · tap Ready"
            } else {
                "Fully open the phone first"
            },
        )
    }

    private fun updateClose(angle: Float, timestampNanos: Long) {
        val stage = stages[state.stageIndex]
        if (legStartedNanos == null && angle <= OPEN_LEAVE_DEGREES) {
            legStartedNanos = timestampNanos
        }
        if (angle <= CLOSED_READY_DEGREES) {
            val feedback = speedFeedback(stage.speed, legStartedNanos, timestampNanos, "Closing")
            legStartedNanos = null
            state = state.copy(
                phase = CalibrationPhase.OPEN,
                instruction = "Now OPEN the phone fully. ${stage.speed.instruction}",
                feedback = feedback,
            )
        }
    }

    private fun updateOpen(angle: Float, timestampNanos: Long) {
        val stage = stages[state.stageIndex]
        if (legStartedNanos == null && angle >= CLOSED_LEAVE_DEGREES) {
            legStartedNanos = timestampNanos
        }
        if (angle < OPEN_READY_DEGREES) return

        val feedback = speedFeedback(stage.speed, legStartedNanos, timestampNanos, "Opening")
        legStartedNanos = null
        val nextCycleInStage = state.cycleInStage + 1
        val completedCycles = state.completedCycles + 1
        if (nextCycleInStage < cyclesPerStage) {
            state = state.copy(
                phase = CalibrationPhase.CLOSE,
                cycleInStage = nextCycleInStage,
                completedCycles = completedCycles,
                instruction = "Cycle ${nextCycleInStage + 1}/$cyclesPerStage: CLOSE fully again. ${stage.speed.instruction}",
                feedback = feedback,
            )
            return
        }

        val nextStage = state.stageIndex + 1
        if (nextStage < stages.size) {
            state = state.copy(
                phase = CalibrationPhase.WAIT_FOR_OPEN,
                stageIndex = nextStage,
                cycleInStage = 0,
                completedCycles = completedCycles,
                instruction = stagePreparationText(stages[nextStage]),
                feedback = feedback,
            )
        } else {
            startStaticSweep(completedCycles, feedback)
        }
    }

    private fun startStaticSweep(completedCycles: Int, feedback: String) {
        holdStartedNanos = null
        state = state.copy(
            phase = CalibrationPhase.STATIC_HOLD,
            completedCycles = completedCycles,
            staticIndex = 0,
            staticTargetDegrees = staticTargets.first(),
            holdProgress = 0f,
            instruction = staticInstruction(staticTargets.first()),
            feedback = feedback,
        )
    }

    private fun updateStatic(angle: Float, timestampNanos: Long) {
        val target = state.staticTargetDegrees ?: return
        if (abs(angle - target) > STATIC_TOLERANCE_DEGREES) {
            holdStartedNanos = null
            state = state.copy(
                holdProgress = 0f,
                instruction = staticInstruction(target),
                feedback = "Set ${target}° · current ${angle.toInt()}°",
            )
            return
        }

        val started = holdStartedNanos ?: timestampNanos.also { holdStartedNanos = it }
        val progress = ((timestampNanos - started).toDouble() / holdNanos).toFloat().coerceIn(0f, 1f)
        if (progress < 1f) {
            state = state.copy(
                holdProgress = progress,
                instruction = "Hold ${target}° steady… ${(progress * 100).toInt()}%",
                feedback = "Within ±${STATIC_TOLERANCE_DEGREES.toInt()}°",
            )
            return
        }

        holdStartedNanos = null
        val next = state.staticIndex + 1
        if (next >= staticTargets.size) {
            state = state.copy(
                phase = CalibrationPhase.COMPLETE,
                running = false,
                staticIndex = staticTargets.size,
                staticTargetDegrees = null,
                holdProgress = 1f,
                instruction = "Calibration complete. Saving dataset…",
                feedback = "Master dataset recorded",
            )
        } else {
            val nextTarget = staticTargets[next]
            state = state.copy(
                staticIndex = next,
                staticTargetDegrees = nextTarget,
                holdProgress = 0f,
                instruction = staticInstruction(nextTarget),
                feedback = "${target}° hold recorded",
            )
        }
    }

    private fun speedFeedback(
        speed: CalibrationSpeed,
        startedNanos: Long?,
        endedNanos: Long,
        label: String,
    ): String {
        if (startedNanos == null || endedNanos <= startedNanos) return "$label recorded"
        val seconds = (endedNanos - startedNanos) / 1_000_000_000f
        val quality = when {
            seconds < speed.minLegSeconds -> "too fast"
            seconds > speed.maxLegSeconds -> "too slow"
            else -> "good pace"
        }
        return "$label ${"%.1f".format(seconds)}s · $quality"
    }

    private fun stagePreparationText(stage: CalibrationStage): String =
        "${stage.orientation.title} · ${stage.speed.title}. ${stage.orientation.instruction} " +
            "Fully open the phone, then tap Ready."

    private fun staticInstruction(target: Int): String =
        "Exact-angle sweep · hold the phone upright with the screen facing you. Set ${target}° and hold for 1.25 seconds."

    private fun withTag(value: CalibrationV2State): CalibrationV2State {
        val tag = when (value.phase) {
            CalibrationPhase.STATIC_HOLD ->
                "cal-v2|mode=static|index=${value.staticIndex + 1}/${value.staticCount}|target=${value.staticTargetDegrees ?: -1}|phase=hold"
            CalibrationPhase.COMPLETE -> "cal-v2|mode=complete"
            CalibrationPhase.IDLE -> "cal-v2|idle"
            else -> {
                val stage = stages[value.stageIndex]
                "cal-v2|mode=dynamic|stage=${value.stageIndex + 1}/${value.stageCount}|orientation=${stage.orientation.id}|" +
                    "speed=${stage.speed.id}|cycle=${value.cycleInStage + 1}/${value.cyclesPerStage}|phase=${value.phase.name.lowercase()}"
            }
        }
        return value.copy(recordingTag = tag)
    }

    private companion object {
        const val OPEN_READY_DEGREES = 170f
        const val OPEN_LEAVE_DEGREES = 164f
        const val CLOSED_READY_DEGREES = 5f
        const val CLOSED_LEAVE_DEGREES = 15f
        const val STATIC_TOLERANCE_DEGREES = 2f
    }
}
