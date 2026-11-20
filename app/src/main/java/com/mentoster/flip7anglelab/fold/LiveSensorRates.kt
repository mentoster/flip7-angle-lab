package com.mentoster.flip7anglelab.fold

/**
 * Small allocation-free rate counter for live Android SensorEvent timestamps.
 * The diagnostic log itself is only sampled at 1 Hz; never infer sensor
 * callback cadence from logcat lines.
 */
class LiveSensorRates {
    enum class Channel { MAGNETOMETER, GRAVITY, GYROSCOPE, HINGE }
    data class Reading(val count: Long, val hz: Float, val lastTimestampNanos: Long)

    private val count = LongArray(Channel.entries.size)
    private val windowStart = LongArray(Channel.entries.size)
    private val windowCount = IntArray(Channel.entries.size)
    private val last = LongArray(Channel.entries.size)
    private val hz = FloatArray(Channel.entries.size)

    fun record(channel: Channel, timestampNanos: Long) {
        if (timestampNanos <= 0L) return
        val i = channel.ordinal
        if (last[i] != 0L && timestampNanos <= last[i]) return
        if (windowStart[i] == 0L) windowStart[i] = timestampNanos
        last[i] = timestampNanos
        count[i]++
        windowCount[i]++
        val elapsed = timestampNanos - windowStart[i]
        if (elapsed >= 500_000_000L) {
            hz[i] = if (windowCount[i] >= 2) {
                ((windowCount[i] - 1) * 1_000_000_000.0 / elapsed).toFloat()
            } else 0f
            windowStart[i] = timestampNanos
            windowCount[i] = 1
        }
    }

    fun reading(channel: Channel): Reading {
        val i = channel.ordinal
        return Reading(count[i], hz[i], last[i])
    }

    companion object {
        fun ageMillis(nowNanos: Long, lastSampleNanos: Long): Long? =
            if (lastSampleNanos <= 0L) null
            else ((nowNanos - lastSampleNanos).coerceAtLeast(0L) / 1_000_000L)

        fun isFresh(nowNanos: Long, sampleTimestampNanos: Long, maxAgeNanos: Long): Boolean =
            sampleTimestampNanos > 0L &&
                nowNanos - sampleTimestampNanos in -50_000_000L..maxAgeNanos
    }
}
