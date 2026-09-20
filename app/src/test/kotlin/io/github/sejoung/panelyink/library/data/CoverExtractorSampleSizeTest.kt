package io.github.sejoung.panelyink.library.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CoverExtractorSampleSizeTest {

    @Test
    fun smallImageIsNotDownsampled() {
        assertEquals(1, CoverExtractor.sampleSizeFor(300, 400, targetMaxPx = 400))
        assertEquals(1, CoverExtractor.sampleSizeFor(500, 799, targetMaxPx = 400))
    }

    @Test
    fun sampledLongSideStaysBetweenTargetAndTwiceTarget() {
        val sizes = listOf(800 to 1200, 1200 to 1800, 1648 to 1236, 4000 to 6000, 6000 to 800)
        for ((w, h) in sizes) {
            val sample = CoverExtractor.sampleSizeFor(w, h, targetMaxPx = 400)
            val longSide = maxOf(w, h) / sample
            assertTrue("$w×$h sample=$sample", longSide >= 400)
            assertTrue("$w×$h sample=$sample", longSide < 800)
        }
    }
}
