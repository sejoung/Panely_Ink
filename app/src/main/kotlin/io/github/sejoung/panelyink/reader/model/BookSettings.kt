package io.github.sejoung.panelyink.reader.model

import io.github.sejoung.panelyink.core.fit.FitMode
import io.github.sejoung.panelyink.core.preferences.ReaderOrientation
import io.github.sejoung.panelyink.core.preferences.ReadingDirection
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
    /**
     * 두쪽 보기(좌/우 동시 표시) 활성화 여부. v1.1 추가.
     * direction과 결합해 LTR이면 `(n, n+1)`을 (좌, 우), RTL이면 (우, 좌)로 그린다.
     * 책별 저장 — 같은 책은 다시 열 때 마지막 선택이 유지되고, 같은 시리즈 propagate에도 함께 따라감.
     */
    val spreadMode: Boolean,
    /**
     * 표지 한 장 단독. v1.1 — 두쪽 보기와 함께만 의미 있음(spreadMode=false면 무시).
     * true면 0쪽(표지)을 단독으로 보여주고 이후를 (1,2)(3,4)…로 짝지어 인쇄본의 펼침면(facing pages)에
     * 정렬한다. false면 (0,1)(2,3)… 기존 동작. 책별 저장 — 같은 책/시리즈 propagation에 함께 따라감.
     */
    val coverAlone: Boolean,
    /**
     * 회전 잠금. v1.1 — 두쪽 보기와 한 쌍. 책별 저장. 본문 화면 진입 시 Activity에 requestedOrientation을 적용,
     * 화면을 떠나면 호스트가 원복([io.github.sejoung.panelyink.reader.ui.ReaderOrientationEffect]).
     */
    val orientation: ReaderOrientation,
) {
    companion object {
        /**
         * 하드코드 fallback — 사용자 명시 override와 [io.github.sejoung.panelyink.core.preferences.AppPreferences]
         * 전역 기본값 둘 다 없을 때 최종 안전 값. 그래서 보수적인 값을 채워둠:
         * - `trimEnabled = false`: 두쪽 보기에서 페이지별 trim 결과가 정렬을 깰 수 있어 OFF가 안전 (v1.1 변경)
         */
        val DEFAULTS: BookSettings = BookSettings(
            fitMode = FitMode.FitScreen,
            direction = ReadingDirection.Ltr,
            trimEnabled = false,
            contrast = ContrastMatrix.IDENTITY,
            spreadMode = false,
            coverAlone = false,
            orientation = ReaderOrientation.Portrait,
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
