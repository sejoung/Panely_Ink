package io.github.sejoung.panelyink.core.render

import org.junit.Assert.assertArrayEquals
import org.junit.Test

class InvertMatrixTest {

    @Test
    fun matrixInvertsRgbAndKeepsAlpha() {
        val m = InvertMatrix.build()
        // R/G/B 행: 채널 -1, 평행이동 +255 — 결과: 255 - in
        // alpha 행: identity (1, 0)
        val expected = floatArrayOf(
            -1f, 0f, 0f, 0f, 255f,
            0f, -1f, 0f, 0f, 255f,
            0f, 0f, -1f, 0f, 255f,
            0f, 0f, 0f, 1f, 0f,
        )
        assertArrayEquals(expected, m, 0.0f)
    }
}
