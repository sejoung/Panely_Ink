package io.github.sejoung.panelyink.core.book

import io.github.sejoung.panelyink.core.position.PositionKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class BookIdentityTest {

    @Test
    fun fromSourceUsesPositionKeyHash() {
        val source = "content://com.example/tree/root/document/book.cbz"

        assertEquals(
            BookId(PositionKey.bookIdFromUri(source)),
            BookIdentity.fromSource(source),
        )
    }

    @Test
    fun fromSourceIsStableForSameSource() {
        val source = "content://com.example/tree/root/document/book.cbz"

        assertEquals(BookIdentity.fromSource(source), BookIdentity.fromSource(source))
    }

    @Test
    fun fromSourceDiffersForNestedEntryName() {
        val parent = "content://com.example/tree/root/document/series.zip"

        assertNotEquals(
            BookIdentity.fromSource("$parent#vol1.cbz"),
            BookIdentity.fromSource("$parent#vol2.cbz"),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun bookIdRejectsEmptyValue() {
        BookId("")
    }
}
