package com.mentoster.flip7anglelab.fold

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DirectImuMathTest {
    @Test
    fun alignedGravityMeansFullyOpen() {
        assertEquals(
            180f,
            DirectImuMath.gravityFoldAngle(floatArrayOf(0f, 0f, 9.81f), floatArrayOf(0f, 0f, 9.81f))!!,
            0.001f,
        )
    }

    @Test
    fun perpendicularGravityMeansNinetyDegreeFold() {
        assertEquals(
            90f,
            DirectImuMath.gravityFoldAngle(floatArrayOf(0f, 0f, 9.81f), floatArrayOf(0f, 9.81f, 0f))!!,
            0.001f,
        )
    }

    @Test
    fun oppositeGravityMeansClosed() {
        assertEquals(
            0f,
            DirectImuMath.gravityFoldAngle(floatArrayOf(0f, 0f, 9.81f), floatArrayOf(0f, 0f, -9.81f))!!,
            0.001f,
        )
    }

    @Test
    fun invalidGravityVectorIsRejected() {
        assertNull(DirectImuMath.gravityFoldAngle(floatArrayOf(0f, 0f, 0f), floatArrayOf(0f, 0f, 9.81f)))
    }
}
