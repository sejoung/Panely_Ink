package io.github.sejoung.panelyink.core.fit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FitCalculatorTest {

    private val viewportW = 1648
    private val viewportH = 1236

    @Test
    fun fitScreenLetterboxesTallPage() {
        // 세로 긴 만화 한 페이지가 7" 가로 화면에 들어갈 때
        val r = FitCalculator.compute(
            pageWidth = 1200,
            pageHeight = 1800,
            viewportWidth = viewportW,
            viewportHeight = viewportH,
            mode = FitMode.FitScreen,
        )
        // 세로 기준이 더 빡빡하므로 height를 viewport에 맞춤
        assertEquals(viewportH, r.drawHeight)
        assertTrue("draw width should be inside viewport", r.drawWidth <= viewportW)
        assertTrue("must be horizontally centered", r.offsetX > 0)
        assertEquals(0, r.offsetY)
    }

    @Test
    fun fitWidthMatchesViewportWidth() {
        val r = FitCalculator.compute(
            pageWidth = 1200,
            pageHeight = 1800,
            viewportWidth = viewportW,
            viewportHeight = viewportH,
            mode = FitMode.FitWidth,
        )
        assertEquals(viewportW, r.drawWidth)
    }

    @Test
    fun zoomFactorIsApplied() {
        val r = FitCalculator.compute(
            pageWidth = 100,
            pageHeight = 100,
            viewportWidth = 500,
            viewportHeight = 500,
            mode = FitMode.Zoom(2f),
        )
        assertEquals(200, r.drawWidth)
        assertEquals(200, r.drawHeight)
    }

    @Test
    fun trimRectShrinksSourceForFitScreen() {
        // 흰 여백이 잔뜩인 1200x1800 페이지 → 본문이 800x1600이라고 가정.
        // FitScreen 결과는 트리밍된 800x1600이 viewport에 맞춰지는 식이어야 한다.
        val r = FitCalculator.compute(
            pageWidth = 1200,
            pageHeight = 1800,
            viewportWidth = 800,
            viewportHeight = 1600,
            mode = FitMode.FitScreen,
            trim = TrimRect(x = 200, y = 100, width = 800, height = 1600),
        )
        assertEquals(800, r.drawWidth)
        assertEquals(1600, r.drawHeight)
        assertEquals(200, r.srcX)
        assertEquals(100, r.srcY)
    }
}
