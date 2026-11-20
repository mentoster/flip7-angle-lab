package com.mentoster.flip7anglelab.fold

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveSensorRatesTest {
    @Test
    fun highRateMagnetometerIsMeasuredIndependentlyOfOneHzLogcat() {
        val monitor = LiveSensorRates()
        val start = 1_000_000_000L
        repeat(101) { i ->
            monitor.record(LiveSensorRates.Channel.MAGNETOMETER, start + i * 10_000_000L)
        }
        val mag = monitor.reading(LiveSensorRates.Channel.MAGNETOMETER)
        assertEquals(101, mag.count)
        assertTrue(mag.hz in 95f..105f)
        assertEquals(start + 1_000_000_000L, mag.lastTimestampNanos)
        assertEquals(0, monitor.reading(LiveSensorRates.Channel.GYROSCOPE).count)
    }

    @Test
    fun detectsStaleSensorStreamEvenWhenAppStillHasLastReading() {
        assertNull(LiveSensorRates.ageMillis(1_000_000_000L, 0))
        assertEquals(850L, LiveSensorRates.ageMillis(1_000_000_000L, 150_000_000L))
        assertFalse(LiveSensorRates.isFresh(1_000_000_000L, 150_000_000L, 400_000_000L))
        assertTrue(LiveSensorRates.isFresh(1_000_000_000L, 950_000_000L, 400_000_000L))
    }

    @Test
    fun duplicateAndOutOfOrderTimestampDoNotCreatePhantomEvents() {
        val monitor = LiveSensorRates()
        monitor.record(LiveSensorRates.Channel.HINGE, 1_000_000_000L)
        monitor.record(LiveSensorRates.Channel.HINGE, 1_000_000_000L)
        monitor.record(LiveSensorRates.Channel.HINGE, 990_000_000L)
        assertEquals(1L, monitor.reading(LiveSensorRates.Channel.HINGE).count)
    }
}
