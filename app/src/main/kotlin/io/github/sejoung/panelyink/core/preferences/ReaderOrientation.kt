package io.github.sejoung.panelyink.core.preferences

/**
 * 본문 리더 회전 잠금. 책별 [io.github.sejoung.panelyink.reader.model.BookSettings]에 저장되며,
 * 두쪽 보기와 짝을 이뤄 사용된다 — 가로 잠금 + 두쪽 보기가 10"+ 디바이스에서의 기본 조합.
 *
 * - [Auto]: 시스템 회전 설정을 따름. v1.0과 동일한 기본 동작.
 * - [Portrait]: 세로 잠금. 가속도계가 어떻든 SENSOR_PORTRAIT로 잡아 위/아래 뒤집기는 허용.
 * - [Landscape]: 가로 잠금. SENSOR_LANDSCAPE — 좌/우 어느 쪽이든 사용자가 잡은 방향대로.
 *
 * 직렬화: Room에는 `name`을 String으로 저장. 알 수 없는 값은 [Auto]로 안전 폴백.
 */
enum class ReaderOrientation {
    Auto,
    Portrait,
    Landscape,
}

/** 깨진/알 수 없는 직렬값은 [ReaderOrientation.Auto] 폴백 — v1.0 동작 유지. */
fun deserializeOrientation(value: String): ReaderOrientation =
    runCatching { ReaderOrientation.valueOf(value) }.getOrDefault(ReaderOrientation.Auto)
