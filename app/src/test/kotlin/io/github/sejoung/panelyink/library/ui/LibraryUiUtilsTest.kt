package io.github.sejoung.panelyink.library.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryUiUtilsTest {

    @Test
    fun formatBytesHandlesUnknownSize() {
        assertEquals("-", formatBytes(0))
        assertEquals("-", formatBytes(-1))
    }

    @Test
    fun formatBytesUsesKbBelowOneMb() {
        assertEquals("512 KB", formatBytes(512L * 1024L))
    }

    @Test
    fun formatBytesUsesMbAtOneMbAndAbove() {
        assertEquals("1.0 MB", formatBytes(1024L * 1024L))
        assertEquals("1.5 MB", formatBytes(1536L * 1024L))
    }
}
