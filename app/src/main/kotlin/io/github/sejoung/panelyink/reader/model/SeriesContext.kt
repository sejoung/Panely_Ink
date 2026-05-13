package io.github.sejoung.panelyink.reader.model

import io.github.sejoung.panelyink.core.book.BookRef

/**
 * 책을 reader로 열 때 같이 전달되는 시리즈 컨텍스트. 라이브러리 진입 시점의
 * "지금 화면에 보이는 책 목록"이 [siblings]로 들어온다.
 *
 * Reader는 이 컨텍스트로 PRD §6.2 시리즈 연속 기능(다음/이전 권 카드, 마지막 페이지
 * "다음 권" 카드, 시리즈 설정 propagate)을 자체 데이터만으로 구현 — `LibraryViewModel`
 * 의존성 없음. 사용자가 다른 책으로 이동해도 호스트가 [current]만 교체해 새 컨텍스트
 * 를 넘기면 됨([siblings]는 같은 시리즈 내부라 재사용 가능).
 *
 * [currentIndex] 가 음수이면 [siblings]에 [current]가 없는 상태(외부 진입 등) — 이 경우
 * 다음/이전 권은 null로 처리(연속 읽기 비활성).
 */
data class SeriesContext(
    val current: BookRef,
    val siblings: List<BookRef>,
) {
    val currentIndex: Int =
        siblings.indexOfFirst { it.bookIdSource == current.bookIdSource }

    val previousBook: BookRef?
        get() = if (currentIndex > 0) siblings[currentIndex - 1] else null

    val nextBook: BookRef?
        get() = if (currentIndex in 0 until (siblings.size - 1)) siblings[currentIndex + 1] else null

    val isFirst: Boolean get() = currentIndex <= 0
    val isLast: Boolean get() = currentIndex < 0 || currentIndex >= siblings.size - 1

    companion object {
        /** 단독 책(형제 없음) — 외부 인텐트 등에서 단일 책으로 진입할 때 사용. */
        fun standalone(book: BookRef): SeriesContext = SeriesContext(book, listOf(book))
    }
}
