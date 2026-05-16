package io.github.sejoung.panelyink.core.preferences

import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import io.github.sejoung.panelyink.core.fit.FitMode
import io.github.sejoung.panelyink.core.render.ContrastMatrix
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
    /**
     * 새 책 첫 진입 시 적용할 두쪽 보기 기본값 — v1.1. 책별 [io.github.sejoung.panelyink.reader.model.BookSettings.spreadMode]가
     * 저장된 책은 그쪽이 우선. 라이브러리 전역 설정에서만 토글 가능.
     */
    val defaultSpreadMode: Boolean,
    /**
     * 새 책 첫 진입 시 적용할 회전 잠금 기본값 — v1.1. 책별 저장값이 있으면 그쪽이 우선.
     */
    val defaultOrientation: ReaderOrientation,
    /**
     * 새 책 첫 진입 시 적용할 자동 여백 트리밍 기본값 — v1.1. 기본 false로 둠 —
     * 두쪽 보기에서 페이지별 trim 결과가 좌/우 정렬을 깰 수 있고, e-ink 사용자는 페이지 잔상보다 일관된 레이아웃을 더 선호.
     * 트림을 원하면 책별/전역에서 명시 ON. 책별 저장값이 있으면 그쪽이 우선.
     */
    val defaultTrimEnabled: Boolean,
    /**
     * 새 책 첫 진입 시 적용할 화면 맞춤 기본값 — v1.1. FitScreen/FitWidth/FitHeight 중 하나.
     * `FitMode.Zoom`은 전역 기본값으로 의미 없어 UI에서 노출 안 함(책별 명시만 가능).
     */
    val defaultFitMode: FitMode,
    /**
     * 새 책 첫 진입 시 적용할 대비 배율 기본값 — v1.1. 0.5..2.0. 1.0 = 원본.
     */
    val defaultContrast: Float,
) {
    companion object {
        val DEFAULTS: AppPreferences = AppPreferences(
            defaultReadingDirection = ReadingDirection.Ltr,
            fullRefreshInterval = DEFAULT_FULL_REFRESH_INTERVAL,
            invertEnabled = false,
            languageTag = AppLanguage.System.tag,
            defaultSpreadMode = false,
            defaultOrientation = ReaderOrientation.Portrait,
            defaultTrimEnabled = false,
            defaultFitMode = FitMode.FitScreen,
            defaultContrast = ContrastMatrix.IDENTITY,
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
    suspend fun setDefaultSpreadMode(value: Boolean)
    suspend fun setDefaultOrientation(value: ReaderOrientation)
    suspend fun setDefaultTrimEnabled(value: Boolean)
    suspend fun setDefaultFitMode(value: FitMode)
    suspend fun setDefaultContrast(value: Float)
}

const val DEFAULT_FULL_REFRESH_INTERVAL: Int = 0
