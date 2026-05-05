package io.github.sejoung.panelyink.reader

/**
 * 본문 진행 방향. PRD §6.1 / Design Guidelines §11.
 *
 * - [Ltr] / [Rtl]: 단일 페이지 모드. 좌/우 탭과 하드웨어 키 매핑이 자동 반전 (Guidelines §11)
 * - [VerticalScroll]: 웹툰. RTL 무시 (PRD §6.1)
 */
enum class ReadingDirection {
    Ltr,
    Rtl,
    VerticalScroll,
}
