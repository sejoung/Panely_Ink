package io.github.sejoung.panelyink.core.position

/**
 * 책의 현재 위치와 전체 길이 — M3 라이브러리 진행률 배지에 사용.
 *
 * - [pageIndex]: 0-base 현재 페이지
 * - [pageCount]: 책의 전체 페이지 수. 사용자가 책을 한 번이라도 열어 [io.github.sejoung.panelyink.core.position.PositionRepository.save]
 *   를 호출해야 채워진다. 미열람 책은 row 자체가 없거나 0이라 [isKnown]이 false.
 *
 * [percent] 수식: `(pageIndex + 1) / pageCount` — 첫 페이지에서 1/N, 마지막에서 100%.
 * 사용자 체감("이만큼 봤다")과 일치.
 */
data class BookProgress(
    val pageIndex: Int,
    val pageCount: Int,
) {
    /** 분모가 0이면 알 수 없음(unknown) — 라이브러리 라벨에 표시하지 않는다. */
    val isKnown: Boolean get() = pageCount > 0

    /** 0.0..1.0 진행률. [isKnown]이 false면 0.0. */
    val percent: Float
        get() = if (pageCount > 0) (pageIndex + 1).toFloat() / pageCount else 0f
}
