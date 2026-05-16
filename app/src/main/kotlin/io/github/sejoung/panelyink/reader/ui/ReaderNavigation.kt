package io.github.sejoung.panelyink.reader.ui

import io.github.sejoung.panelyink.core.book.BookRef
import io.github.sejoung.panelyink.reader.ReaderState
import io.github.sejoung.panelyink.reader.model.SeriesContext

internal fun previousBookForBoundary(
  state: ReaderState,
  context: SeriesContext,
): BookRef? = if (state.currentPage == 0) context.previousBook else null

internal fun nextBookForBoundary(
  state: ReaderState,
  pageCount: Int,
  context: SeriesContext,
): BookRef? = if (state.currentPage == pageCount - 1) context.nextBook else null
