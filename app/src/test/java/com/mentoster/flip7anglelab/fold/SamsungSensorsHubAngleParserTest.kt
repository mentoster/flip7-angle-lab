package com.mentoster.flip7anglelab.fold

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SamsungSensorsHubAngleParserTest {
    @Test
    fun parsesFlip7SensorsHubFoldingAngle() {
        val line = "I/SensorsHub( 1507): FoldingAngle: event = 123, time = 559812222432695"
        assertEquals(123f, SamsungSensorsHubAngleParser.parse(line)!!, 0f)
        val event = requireNotNull(SamsungSensorsHubAngleParser.parseEvent(line))
        assertEquals(559812222432695L, event.timestampNanos)
    }

    @Test
    fun clampsAndRejectsUnrelatedSensorHubLines() {
        assertEquals(180f, SamsungSensorsHubAngleParser.parse("FoldingAngle: event = 240")!!, 0f)
        assertEquals(0f, SamsungSensorsHubAngleParser.parse("FoldingAngle: event = -5")!!, 0f)
        assertNull(SamsungSensorsHubAngleParser.parse("LidAngleFusion: angle = 90"))
        assertNull(SamsungSensorsHubAngleParser.parse("HingeAngle: event = 90"))
    }

    @Test
    fun bootstrapEventRetainsOriginalTimestampInsteadOfBecomingFreshOnAppStart() {
        val event = requireNotNull(SamsungSensorsHubAngleParser.parseEvent(
            "I/SensorsHub( 1139): FoldingAngle: event = 177, time = 27730613873320",
        ))
        assertEquals(177f, event.angle, 0f)
        assertEquals(27730613873320L, event.timestampNanos)
        assertEquals(null, SamsungSensorsHubAngleParser.parseEvent(
            "FoldingAngle: event = 177",
        )?.timestampNanos)
    }
}
