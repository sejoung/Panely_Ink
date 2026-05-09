package io.github.sejoung.panelyink.core.preferences

import io.github.sejoung.panelyink.reader.ReaderViewModel

/**
 * 앱 전역 표시/디스플레이 설정 — 디바이스 특성/환경 의존 옵션.
 *
 * 책별 저장이 자연스럽지 않은 옵션들을 모음:
 * - [fullRefreshInterval]: e-ink 디바이스 자체의 잔상 정책. 책마다 달라야 할 이유가 약함
 * - [invertEnabled]: 야간/조명 환경에 따라 토글 — 환경 의존이라 모든 책에 일관
 *
 * 책별([io.github.sejoung.panelyink.reader.model.BookSettings])과 분리. 사용자가 라이브러리
 * 앱 설정에서 변경하거나, 책 메뉴에서도 토글 가능(흑백 반전) — 어느 쪽이든 같은
 * 전역 저장소에 저장되어 모든 책에 즉시 반영.
 */
data class AppPreferences(
    val fullRefreshInterval: Int,
    val invertEnabled: Boolean,
) {
    companion object {
        val DEFAULTS: AppPreferences = AppPreferences(
            fullRefreshInterval = ReaderViewModel.FULL_REFRESH_INTERVAL_DEFAULT,
            invertEnabled = false,
        )
    }
}

/**
 * `AppPreferences` 영속 — 글로벌 키-값 데이터라 SharedPreferences로 충분(Room 불필요).
 */
interface AppPreferencesRepository {
    suspend fun load(): AppPreferences
    suspend fun setFullRefreshInterval(value: Int)
    suspend fun setInvertEnabled(value: Boolean)
}
