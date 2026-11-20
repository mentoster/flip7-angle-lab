package com.mentoster.flip7anglelab.fold

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SamsungAngleParserTest {
    @Test
    fun parsesFlipFactoryRefAngle() {
        assertEquals(178f, FlipRefAngleParser.parse("178,0")!!, 0f)
        assertEquals(90f, FlipRefAngleParser.parse(" 90,0 ")!!, 0f)
        assertEquals(0f, FlipRefAngleParser.parse("-2,0")!!, 0f)
        assertNull(FlipRefAngleParser.parse("not-an-angle"))
    }

    @Test
    fun clampsFactoryAngleAndRejectsUnrelatedLines() {
        assertEquals(180f, FlipRefAngleParser.parse("240,0")!!, 0f)
        assertEquals(0f, FlipRefAngleParser.parse("-12.5,0")!!, 0f)
        assertNull(FlipRefAngleParser.parse("hinge=90"))
    }
}
