package io.github.sejoung.panelyink.reader.model

import android.net.Uri

/**
 * Reader session boundary model. Library scanning may produce richer entries, but the
 * reader only needs a stable source id plus the URI/nested entry needed to open bytes.
 */
data class ReadableBookRef(
    val documentUri: Uri,
    val displayName: String,
    val sizeBytes: Long,
    val sourceId: String,
    val nestedEntryName: String? = null,
)
