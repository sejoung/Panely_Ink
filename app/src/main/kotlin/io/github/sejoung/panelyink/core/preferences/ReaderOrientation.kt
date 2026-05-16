package io.github.sejoung.panelyink.core.preferences

/**
 * 본문 리더 회전 잠금. 책별 [io.github.sejoung.panelyink.reader.model.BookSettings]와
 * 앱 전역 기본값([AppPreferences.defaultOrientation])에서 함께 사용된다.
 *
 * - [Portrait]: 세로 — v1.0과 동일. 기본값.
 * - [Landscape]: 가로 — 두쪽 보기와 함께 쓰면 자연스럽게 좌/우 페이지가 정렬됨.
 *
 * **Auto는 v1.1 초기 설계에 있었으나 제거됨**: Mee OS 같은 e-ink OEM이
 * `Activity.requestedOrientation = UNSPECIFIED`을 받아도 시스템 자동회전 설정이 꺼져 있어
 * 실질적으로 Portrait와 동일하게 동작. 의미 있는 옵션이 아니라 사용자 혼란만 가중되어 제거.
 * 레거시 DB 값 `"Auto"`는 [deserializeOrientation]이 [Portrait]로 폴백한다.
 *
 * 직렬화: Room에는 `name`을 String으로 저장.
 */
enum class ReaderOrientation {
    Portrait,
    Landscape,
}

/** 깨진/알 수 없는 직렬값(레거시 `"Auto"` 포함)은 [ReaderOrientation.Portrait] 폴백 — v1.0 동작 유지. */
fun deserializeOrientation(value: String): ReaderOrientation =
    runCatching { ReaderOrientation.valueOf(value) }.getOrDefault(ReaderOrientation.Portrait)
