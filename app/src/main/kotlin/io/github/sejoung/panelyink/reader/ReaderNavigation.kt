package io.github.sejoung.panelyink.reader

import io.github.sejoung.panelyink.library.BookEntry

internal fun ReaderState.toBookSettings(): BookSettings = BookSettings(
    fitMode = fitMode,
    direction = direction,
    trimEnabled = trimEnabled,
    contrast = contrast,
)

internal fun previousBookForBoundary(
    state: ReaderState,
    context: SeriesContext,
): BookEntry? = if (state.currentPage == 0) context.previousBook else null

internal fun nextBookForBoundary(
    state: ReaderState,
    pageCount: Int,
    context: SeriesContext,
): BookEntry? = if (state.currentPage == pageCount - 1) context.nextBook else null
