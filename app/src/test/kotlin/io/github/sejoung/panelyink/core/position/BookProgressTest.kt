package io.github.sejoung.panelyink.core.position

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BookProgressTest {

    @Test
    fun zeroPageCountIsUnknown() {
        val p = BookProgress(pageIndex = 0, pageCount = 0)
        assertFalse(p.isKnown)
        assertEquals(0f, p.percent, 0.0001f)
    }

    @Test
    fun firstPageOfHundredIsOnePercent() {
        val p = BookProgress(pageIndex = 0, pageCount = 100)
        assertTrue(p.isKnown)
        // (0+1)/100 = 0.01
        assertEquals(0.01f, p.percent, 0.0001f)
    }

    @Test
    fun lastPageIsHundredPercent() {
        val p = BookProgress(pageIndex = 99, pageCount = 100)
        assertEquals(1.0f, p.percent, 0.0001f)
    }

    @Test
    fun singlePageBookIsHundredPercent() {
        val p = BookProgress(pageIndex = 0, pageCount = 1)
        assertEquals(1.0f, p.percent, 0.0001f)
    }
}
