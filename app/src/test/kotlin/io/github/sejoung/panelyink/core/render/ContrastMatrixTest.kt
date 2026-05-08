package io.github.sejoung.panelyink.core.render

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class ContrastMatrixTest {

    private val tolerance = 0.0001f

    @Test
    fun identityWhenContrastIsOne() {
        val m = ContrastMatrix.build(1.0f)
        // s=1, t=0 — color channels는 ×1, alpha는 항등
        val expected = floatArrayOf(
            1f, 0f, 0f, 0f, 0f,
            0f, 1f, 0f, 0f, 0f,
            0f, 0f, 1f, 0f, 0f,
            0f, 0f, 0f, 1f, 0f,
        )
        assertArrayEquals(expected, m, tolerance)
    }

    @Test
    fun doubledContrastTranslateIsMinus127_5() {
        val m = ContrastMatrix.build(2.0f)
        // s=2, t=(1-2)*127.5 = -127.5
        // R 채널 행: 2,0,0,0,-127.5
        assertEquals(2.0f, m[0], tolerance)
        assertEquals(-127.5f, m[4], tolerance)
        // G/B 행도 동일
        assertEquals(2.0f, m[6], tolerance)
        assertEquals(-127.5f, m[9], tolerance)
        assertEquals(2.0f, m[12], tolerance)
        assertEquals(-127.5f, m[14], tolerance)
        // alpha 행은 그대로
        assertEquals(1.0f, m[18], tolerance)
        assertEquals(0.0f, m[19], tolerance)
    }

    @Test
    fun halfContrastShiftsTowardMidGray() {
        val m = ContrastMatrix.build(0.5f)
        // s=0.5, t=(1-0.5)*127.5 = 63.75
        assertEquals(0.5f, m[0], tolerance)
        assertEquals(63.75f, m[4], tolerance)
    }

    @Test
    fun matrixIs4x5EquivalentLength() {
        // ColorMatrix는 4×5 = 20개 float
        assertEquals(20, ContrastMatrix.build(1.5f).size)
    }

    @Test(expected = IllegalArgumentException::class)
    fun zeroContrastIsRejected() {
        ContrastMatrix.build(0f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun negativeContrastIsRejected() {
        ContrastMatrix.build(-1f)
    }
}
