package io.github.sejoung.panelyink.core.book

import android.net.Uri

/**
 * Feature-neutral reference to a readable book resource.
 *
 * Library scanning owns richer UI entries, but persistence and reader navigation only need this
 * stable reference shape.
 */
data class BookRef(
    val documentUri: Uri,
    val displayName: String,
    val sizeBytes: Long,
    val mimeType: String?,
    val rootUri: Uri,
    val nestedEntryName: String? = null,
) {
    val bookIdSource: String
        get() = if (nestedEntryName != null) "$documentUri#$nestedEntryName" else documentUri.toString()

    val bookId: BookId
        get() = BookIdentity.fromSource(bookIdSource)
}

data class IndexedBookRef(
    val book: BookRef,
    val siblings: List<BookRef>,
    val groupKey: String,
)
