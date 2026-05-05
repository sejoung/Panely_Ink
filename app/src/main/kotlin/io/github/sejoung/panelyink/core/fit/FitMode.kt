package io.github.sejoung.panelyink.core.fit

/**
 * 본문 페이지를 뷰포트에 맞추는 방식. PRD §6.1 "Fit modes" 와 1:1.
 *
 * - [FitScreen]   화면에 통째로 들어가도록(letterbox 허용)
 * - [FitWidth]    가로폭 기준 (세로는 잘릴 수 있음, 페이지 단위 점프)
 * - [FitHeight]   세로폭 기준 (가로는 잘릴 수 있음)
 * - [Zoom]        사용자 정의 배율 — PRD에 명시된 단계는 100/125/150/200%
 */
sealed interface FitMode {
    data object FitScreen : FitMode
    data object FitWidth : FitMode
    data object FitHeight : FitMode
    data class Zoom(val factor: Float) : FitMode {
        init { require(factor > 0f) { "zoom factor must be positive" } }
    }
}
