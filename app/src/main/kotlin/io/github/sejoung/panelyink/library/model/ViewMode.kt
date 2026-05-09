package io.github.sejoung.panelyink.library.model

/**
 * 라이브러리 표시 모드.
 *
 * - [List]: 텍스트 위주 한 줄. 표지 추출 안 함(첫 진입/스크롤 가벼움).
 * - [Cover]: 좌측 표지 + 우측 텍스트(현재 기본).
 * - [Grid]: 3열 그리드. 표지 위주, 텍스트 2줄.
 *
 * e-ink 디바이스에선 표지 비트맵이 잔상에 부담이 되어 사용자가 List 모드를 선호할 수
 * 있다. 정렬과 직교 — 두 옵션은 [LibraryOptionsDialog]에서 한 번에 조정.
 */
enum class ViewMode {
    List,
    Cover,
    Grid,
    ;

    companion object {
        val DEFAULT = Cover

        fun fromKey(key: String?): ViewMode = entries.firstOrNull { it.name == key } ?: DEFAULT
    }
}
