package io.github.sejoung.panelyink.reader.input

import android.view.KeyEvent

/**
 * 본문에서 받는 하드웨어 키를 페이지 이동 의도로 변환한다.
 *
 * Design Guidelines §11 — **하드웨어 키는 LTR/RTL 무관하게 사용자 기대값 유지**.
 * 즉 우측 키 = 다음, 좌측 키 = 이전 (불변). RTL 반전은 화면 탭 영역에만 적용.
 *
 * v1.0 매핑 (PRD §6.1 입력):
 * - 볼륨 ↑ / Page Up / DPad Left   → 이전
 * - 볼륨 ↓ / Page Down / DPad Right → 다음
 *
 * Meebook M7 좌/우 페이지 키가 어떤 KeyCode를 뱉는지는 실기 학습 단계
 * (M0.5 spike 또는 M5 베타)에서 사용자 리바인드 화면으로 확장 예정.
 */
object ReaderInput {

    /** [event]가 페이지 이동으로 매핑되면 [onPrev] / [onNext] 호출 후 true. 아니면 false. */
    fun dispatch(
        event: KeyEvent,
        onPrev: () -> Unit,
        onNext: () -> Unit,
    ): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) {
            // 매핑된 키는 ACTION_UP도 consume — 시스템에 누수되면 볼륨 변동 등 부작용
            return event.keyCode in CONSUMED_KEYS
        }
        return when (event.keyCode) {
            in PREV_KEYS -> { onPrev(); true }
            in NEXT_KEYS -> { onNext(); true }
            else -> false
        }
    }

    private val PREV_KEYS = setOf(
        KeyEvent.KEYCODE_VOLUME_UP,
        KeyEvent.KEYCODE_PAGE_UP,
        KeyEvent.KEYCODE_DPAD_LEFT,
    )

    private val NEXT_KEYS = setOf(
        KeyEvent.KEYCODE_VOLUME_DOWN,
        KeyEvent.KEYCODE_PAGE_DOWN,
        KeyEvent.KEYCODE_DPAD_RIGHT,
    )

    private val CONSUMED_KEYS = PREV_KEYS + NEXT_KEYS
}
