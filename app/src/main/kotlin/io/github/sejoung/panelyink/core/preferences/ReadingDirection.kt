package io.github.sejoung.panelyink.core.preferences

/**
 * 본문 진행 방향. 앱 기본값과 책별 리더 설정에서 함께 사용된다.
 *
 * - [Ltr] / [Rtl]: 단일 페이지 모드. 좌/우 탭과 하드웨어 키 매핑이 자동 반전된다.
 * - [VerticalScroll]: 웹툰. RTL 무시.
 */
enum class ReadingDirection {
    Ltr,
    Rtl,
    VerticalScroll,
}
