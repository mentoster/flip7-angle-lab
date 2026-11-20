package com.mentoster.flip7anglelab.fold

import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.math.roundToInt

/**
 * Small on-device, reference-trained correction layer for the offline MLP.
 *
 * Public Android hinge states are too coarse to treat 180° as fully open:
 * on the Flip7 that state also occurs at 150–165°. Corrections therefore require
 * nearby magnetic AND gravity features from previously labeled Samsung samples.
 * Prediction never reads the current exact reference angle.
 */
internal class AdaptiveAngleCalibration(
    private val maxSamples: Int = 1500,
) {
    internal var lastMatchMode: String = "none"
        private set
    data class Sample(
        val magneticX: Float,
        val magneticY: Float,
        val magneticZ: Float,
        val gravityX: Float,
        val gravityY: Float,
        val gravityZ: Float,
        val publicHinge: Float,
        val exactAngle: Float,
    ) {
        fun serialize(): String = listOf(
            magneticX, magneticY, magneticZ, gravityX, gravityY, gravityZ, publicHinge, exactAngle,
        ).joinToString(",")

        companion object {
            fun parse(line: String): Sample? {
                val fields = line.split(',').mapNotNull(String::toFloatOrNull)
                if (fields.size != 8 || !fields.all(Float::isFinite)) return null
                if (fields[6] !in 0f..180f || fields[7] !in 0f..180f) return null
                return Sample(
                    fields[0], fields[1], fields[2], fields[3],
                    fields[4], fields[5], fields[6], fields[7],
                )
            }
        }
    }

    private val points = ArrayDeque<Sample>()
    val sampleCount: Int get() = points.size

    fun observe(sample: Sample): Boolean {
        if (!isValid(sample)) return false
        val latest = points.lastOrNull()
        // Avoid filling the memory with repeated stationary frames.
        if (latest != null && abs(latest.exactAngle - sample.exactAngle) < 0.75f &&
            distanceSquared(latest, sample) < 0.03f
        ) return false

        // A FIFO cache lost almost the entire calibration: on a real Flip7,
        // 1,382/1,500 records became one stationary ~90° orientation.
        // Keep a *diverse* collection of angle AND gravity-pose buckets instead.
        val group = groupKey(sample)
        val sameGroup = points.indices.filter { groupKey(points[it]) == group }
        val removalIndex = when {
            sameGroup.size >= MAX_PER_GROUP -> sameGroup.first()
            points.size < maxSamples -> -1
            else -> {
                val counts = HashMap<Int, Int>()
                points.forEach { item ->
                    val key = groupKey(item)
                    counts[key] = (counts[key] ?: 0) + 1
                }
                val largestGroup = counts.maxByOrNull { it.value }?.key
                points.indexOfFirst { groupKey(it) == largestGroup }.coerceAtLeast(0)
            }
        }
        if (removalIndex >= 0) points.removeAt(removalIndex)
        points.addLast(sample)
        return true
    }

    /**
     * Try exact pose neighbors first, then moderately relaxed orientation
     * neighbors. Reject conflicting labels instead of jumping to a single
     * accidental match when the entire phone is rotated in space.
     */
    fun predict(query: Sample): Float? {
        lastMatchMode = "none"
        if (!isValid(query) || points.isEmpty()) return null
        val strict = neighbors(
            query, magneticScale = 35f, gravityScale = 3f,
            maxDistanceSquared = 0.4f,
        )
        if (strict != null) {
            lastMatchMode = "strict"
            return strict
        }
        if (points.size < MIN_SAMPLES_FOR_RELAXED_MATCH) return null
        val relaxed = neighbors(
            query, magneticScale = 50f, gravityScale = 7f,
            maxDistanceSquared = 1.5f,
        )
        if (relaxed != null) lastMatchMode = "orientation-tolerant"
        return relaxed
    }

    private fun neighbors(
        query: Sample,
        magneticScale: Float,
        gravityScale: Float,
        maxDistanceSquared: Float,
    ): Float? {
        val nearest = ArrayList<Pair<Sample, Float>>(NEIGHBORS)
        for (point in points) {
            if (abs(point.publicHinge - query.publicHinge) > 20f) continue
            val distance = distanceSquared(point, query, magneticScale, gravityScale)
            if (distance > maxDistanceSquared) continue
            val position = nearest.indexOfFirst { it.second > distance }
                .let { if (it == -1) nearest.size else it }
            if (position == NEIGHBORS) continue
            nearest.add(position, point to distance)
            if (nearest.size > NEIGHBORS) nearest.removeAt(NEIGHBORS)
        }
        val minimum = if (points.size < MIN_SAMPLES_FOR_RELAXED_MATCH) 1 else 2
        if (nearest.size < minimum) return null
        val labels = nearest.map { it.first.exactAngle }
        if (points.size >= MIN_SAMPLES_FOR_RELAXED_MATCH &&
            labels.max() - labels.min() > MAX_LABEL_SPREAD_DEGREES
        ) return null
        var weightedAngle = 0.0
        var weightTotal = 0.0
        nearest.forEach { (point, distance) ->
            val weight = 1.0 / (0.02 + distance)
            weightedAngle += weight * point.exactAngle
            weightTotal += weight
        }
        return (weightedAngle / weightTotal).toFloat().coerceIn(0f, 180f)
    }

    fun restore(samples: Sequence<Sample>) {
        points.clear()
        // Keep all previously collected data on upgrade. Diversity limits
        // apply to NEW observations; loading must not silently discard a
        // user's existing learning cache.
        samples.filter(::isValid).forEach { sample ->
            if (points.size >= maxSamples) points.removeFirst()
            points.addLast(sample)
        }
    }

    fun snapshot(): List<Sample> = points.toList()

    /**
     * An approximately 5° angle bin crossed with a gravity-direction cell.
     * Quantization prevents stationary sensor noise from consuming the cache.
     * This is only used for exemplar retention, not as an inference feature.
     */
    private fun groupKey(sample: Sample): Int {
        val angle = (sample.exactAngle / 5f).roundToInt().coerceIn(0, 36)
        val gx = (sample.gravityX / 4f).roundToInt().coerceIn(-3, 3) + 3
        val gy = (sample.gravityY / 4f).roundToInt().coerceIn(-3, 3) + 3
        val gz = (sample.gravityZ / 4f).roundToInt().coerceIn(-3, 3) + 3
        return ((angle * 7 + gx) * 7 + gy) * 7 + gz
    }

    private fun isValid(sample: Sample): Boolean =
        listOf(
            sample.magneticX, sample.magneticY, sample.magneticZ,
            sample.gravityX, sample.gravityY, sample.gravityZ,
            sample.publicHinge, sample.exactAngle,
        ).all(Float::isFinite) &&
            sample.publicHinge in 0f..180f &&
            sample.exactAngle in 0f..180f &&
            sqrt(
                sample.gravityX * sample.gravityX +
                    sample.gravityY * sample.gravityY +
                    sample.gravityZ * sample.gravityZ,
            ) >= 0.5f

    private fun distanceSquared(
        a: Sample,
        b: Sample,
        magneticScale: Float = 35f,
        gravityScale: Float = 3f,
    ): Float {
        val dx = (a.magneticX - b.magneticX) / magneticScale
        val dy = (a.magneticY - b.magneticY) / magneticScale
        val dz = (a.magneticZ - b.magneticZ) / magneticScale
        val dgx = (a.gravityX - b.gravityX) / gravityScale
        val dgy = (a.gravityY - b.gravityY) / gravityScale
        val dgz = (a.gravityZ - b.gravityZ) / gravityScale
        return dx * dx + dy * dy + dz * dz + dgx * dgx + dgy * dgy + dgz * dgz
    }

    private companion object {
        const val NEIGHBORS = 5
        const val MIN_SAMPLES_FOR_RELAXED_MATCH = 10
        const val MAX_LABEL_SPREAD_DEGREES = 20f
        const val MAX_PER_GROUP = 12
    }
}
