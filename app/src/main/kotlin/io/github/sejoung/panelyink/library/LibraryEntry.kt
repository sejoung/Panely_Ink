package io.github.sejoung.panelyink.library

import android.net.Uri

/** 라이브러리 목록의 한 행. 책(.cbz/.zip 1권)을 표현. */
data class LibraryEntry(
    val documentUri: Uri,
    val displayName: String,
    val sizeBytes: Long,
    val mimeType: String?,
    val rootUri: Uri,
)
