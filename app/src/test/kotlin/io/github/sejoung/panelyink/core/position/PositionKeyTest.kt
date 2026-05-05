package io.github.sejoung.panelyink.core.position

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class PositionKeyTest {

    @Test
    fun storageKeyRoundTrips() {
        val key = PositionKey(bookId = "abc123", pageIndex = 42)
        val s = key.toStorageKey()
        assertEquals(key, PositionKey.fromStorageKey(s))
    }

    @Test
    fun bookIdIsStableForSameUri() {
        val a = PositionKey.bookIdFromUri("content://com.example/tree/foo/bar.cbz")
        val b = PositionKey.bookIdFromUri("content://com.example/tree/foo/bar.cbz")
        assertEquals(a, b)
    }

    @Test
    fun bookIdDiffersForDifferentUris() {
        val a = PositionKey.bookIdFromUri("content://com.example/tree/foo/a.cbz")
        val b = PositionKey.bookIdFromUri("content://com.example/tree/foo/b.cbz")
        assertNotEquals(a, b)
    }
}
