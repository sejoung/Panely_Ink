package io.github.sejoung.panelyink.reader.model

import io.github.sejoung.panelyink.core.fit.FitMode
import io.github.sejoung.panelyink.core.render.ContrastMatrix

/**
 * 책별로 영속 저장되는 사용자 설정 묶음 — PRD §6.1 "Contrast/Gamma 책별 저장",
 * "같은 시리즈 다음 권에 책별 설정 propagate"의 기반.
 *
 * 페이지 위치([io.github.sejoung.panelyink.core.position.PositionKey])와 별개로 관리.
 * 위치는 매 페이지 이동마다 갱신 빈도가 높지만, 설정은 사용자가 책당 1~2회만 바꾸므로
 * 분리해 저장(Room 두 테이블).
 *
 * 전역 의미가 강한 옵션(`invertEnabled`, `fullRefreshInterval`)은
 * [io.github.sejoung.panelyink.core.preferences.AppPreferences]로 분리(2026-05-08).
 *
 * Room 직렬화는 [serializeFitMode] / [deserializeFitMode] / [ReadingDirection.name]을
 * 사용 — Room이 sealed/enum을 직접 다루지 못하므로 String으로 우회.
 */
data class BookSettings(
    val fitMode: FitMode,
    val direction: ReadingDirection,
    val trimEnabled: Boolean,
    val contrast: Float,
) {
    companion object {
        /** 새 책 진입 시 기본값. ReaderState 기본값과 일치해야 일관됨. */
        val DEFAULTS: BookSettings = BookSettings(
            fitMode = FitMode.FitScreen,
            direction = ReadingDirection.Ltr,
            trimEnabled = true,
            contrast = ContrastMatrix.IDENTITY,
        )
    }
}

/**
 * [FitMode]를 단일 String으로 직렬화. [FitMode.Zoom]은 factor를 ":" 뒤에 붙여 보존.
 *
 * 예시:
 * - `FitMode.FitScreen` → `"FitScreen"`
 * - `FitMode.Zoom(1.5f)` → `"Zoom:1.5"`
 */
fun serializeFitMode(mode: FitMode): String = when (mode) {
    FitMode.FitScreen -> "FitScreen"
    FitMode.FitWidth -> "FitWidth"
    FitMode.FitHeight -> "FitHeight"
    is FitMode.Zoom -> "Zoom:${mode.factor}"
}

/** 알 수 없거나 깨진 값은 [FitMode.FitScreen]으로 안전 폴백. */
fun deserializeFitMode(value: String): FitMode = when {
    value == "FitScreen" -> FitMode.FitScreen
    value == "FitWidth" -> FitMode.FitWidth
    value == "FitHeight" -> FitMode.FitHeight
    value.startsWith("Zoom:") -> {
        val factor = value.substringAfter(":").toFloatOrNull()
        if (factor != null && factor > 0f) FitMode.Zoom(factor) else FitMode.FitScreen
    }
    else -> FitMode.FitScreen
}

/** 알 수 없는 enum 값은 [ReadingDirection.Ltr]로 안전 폴백 — 사용자가 메뉴에서 다시 선택. */
fun deserializeDirection(value: String): ReadingDirection =
    runCatching { ReadingDirection.valueOf(value) }.getOrDefault(ReadingDirection.Ltr)
