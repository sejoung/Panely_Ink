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
): BookRef? =
  // 두쪽 페어에서는 currentPage(leading)가 아니라 화면에 보이는 마지막 페이지로 판정한다 —
  // 표지 단독 + 홀수 쪽수 책의 마지막 spread는 leading이 pageCount - 2라 다음 권으로 못 넘어갔다.
  if (state.lastVisiblePage(pageCount) == pageCount - 1) context.nextBook else null
