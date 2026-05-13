package io.github.sejoung.panelyink.library.model

import io.github.sejoung.panelyink.core.book.BookId
import io.github.sejoung.panelyink.core.book.BookIdentity

/** Durable id used by position, bookmark, settings, and cover metadata stores. */
val BookEntry.bookId: BookId
    get() = BookIdentity.fromSource(bookIdSource)
