package io.github.sejoung.panelyink.library.model

import io.github.sejoung.panelyink.core.book.BookId
import io.github.sejoung.panelyink.core.book.BookIdentity
import io.github.sejoung.panelyink.core.book.BookRef

/** Durable id used by position, bookmark, settings, and cover metadata stores. */
val BookEntry.bookId: BookId
    get() = BookIdentity.fromSource(bookIdSource)

fun BookEntry.toBookRef(): BookRef = BookRef(
    documentUri = documentUri,
    displayName = displayName,
    sizeBytes = sizeBytes,
    mimeType = mimeType,
    rootUri = rootUri,
    nestedEntryName = nestedEntryName,
)
