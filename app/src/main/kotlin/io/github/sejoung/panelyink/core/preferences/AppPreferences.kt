package io.github.sejoung.panelyink.core.preferences

import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import java.util.Locale

/**
 * 앱 전역 표시/디스플레이 설정 — 디바이스 특성/환경 의존 옵션.
 *
 * 책별 저장이 자연스럽지 않은 옵션들을 모음:
 * - [defaultReadingDirection]: 새 책 첫 진입 시 적용할 기본 읽기 방향
 * - [fullRefreshInterval]: e-ink 디바이스 자체의 잔상 정책. 책마다 달라야 할 이유가 약함
 * - [invertEnabled]: 야간/조명 환경에 따라 토글 — 환경 의존이라 모든 책에 일관
 * - [languageTag]: 기본은 시스템 언어. 지원하지 않는 시스템 언어는 기본 리소스(영어) 사용
 *
 * 책별 리더 설정과 분리. 사용자가 라이브러리
 * 앱 설정에서 변경하거나, 책 메뉴에서도 토글 가능(흑백 반전) — 어느 쪽이든 같은
 * 전역 저장소에 저장되어 모든 책에 즉시 반영.
 */
data class AppPreferences(
    val defaultReadingDirection: ReadingDirection,
    val fullRefreshInterval: Int,
    val invertEnabled: Boolean,
    val languageTag: String,
) {
    companion object {
        val DEFAULTS: AppPreferences = AppPreferences(
            defaultReadingDirection = ReadingDirection.Ltr,
            fullRefreshInterval = DEFAULT_FULL_REFRESH_INTERVAL,
            invertEnabled = false,
            languageTag = AppLanguage.System.tag,
        )
    }
}

enum class AppLanguage(val tag: String) {
    English("en"),
    Korean("ko"),
    System("system");

    companion object {
        fun fromTag(tag: String?): AppLanguage =
            entries.firstOrNull { it.tag == tag } ?: English
    }
}

object AppLocale {
    const val PREFS_NAME = "panely_ink_app_prefs"
    const val KEY_LANGUAGE_TAG = "language_tag"

    fun wrapFromPreferences(context: Context): Context {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val tag = prefs.getString(KEY_LANGUAGE_TAG, AppPreferences.DEFAULTS.languageTag)
        return wrap(context, tag)
    }

    fun wrap(context: Context, languageTag: String?): Context {
        val language = AppLanguage.fromTag(languageTag)
        if (language == AppLanguage.System) return context
        val locale = Locale.forLanguageTag(language.tag)
        Locale.setDefault(locale)
        val config = Configuration(context.resources.configuration)
        config.setLocale(locale)
        config.setLayoutDirection(locale)
        return ContextWrapper(context.createConfigurationContext(config))
    }
}

/**
 * `AppPreferences` 영속 — 글로벌 키-값 데이터라 SharedPreferences로 충분(Room 불필요).
 */
interface AppPreferencesRepository {
    suspend fun load(): AppPreferences
    suspend fun setDefaultReadingDirection(value: ReadingDirection)
    suspend fun setFullRefreshInterval(value: Int)
    suspend fun setInvertEnabled(value: Boolean)
    suspend fun setLanguageTag(value: String)
}

const val DEFAULT_FULL_REFRESH_INTERVAL: Int = 0
