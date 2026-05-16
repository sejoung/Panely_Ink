package io.github.sejoung.panelyink.reader.ui

import io.github.sejoung.panelyink.core.book.BookRef
import io.github.sejoung.panelyink.reader.ReaderState
import io.github.sejoung.panelyink.reader.model.BookSettings
import io.github.sejoung.panelyink.reader.model.SeriesContext

internal fun ReaderState.toBookSettings(): BookSettings = BookSettings(
  fitMode = fitMode,
  direction = direction,
  trimEnabled = trimEnabled,
  contrast = contrast,
  spreadMode = spreadMode,
  orientation = orientation,
)

internal fun previousBookForBoundary(
  state: ReaderState,
  context: SeriesContext,
): BookRef? = if (state.currentPage == 0) context.previousBook else null

internal fun nextBookForBoundary(
  state: ReaderState,
  pageCount: Int,
  context: SeriesContext,
): BookRef? = if (state.currentPage == pageCount - 1) context.nextBook else null
