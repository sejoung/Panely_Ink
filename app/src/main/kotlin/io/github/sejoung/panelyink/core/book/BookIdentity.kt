package io.github.sejoung.panelyink.core.book

import io.github.sejoung.panelyink.core.position.PositionKey
import io.github.sejoung.panelyink.library.model.BookEntry

/**
 * Stable identity for per-book reading data.
 *
 * BookEntry is a scanned library resource. BookId is the durable key used by
 * position, bookmarks, settings, and cover metadata.
 */
@JvmInline
value class BookId(val value: String) {
    init {
        require(value.isNotEmpty()) { "bookId must not be empty" }
    }
}

object BookIdentity {
    fun fromSource(source: String): BookId = BookId(PositionKey.bookIdFromUri(source))
    fun fromEntry(entry: BookEntry): BookId = fromSource(entry.bookIdSource)
}
