package io.github.sejoung.panelyink.data.preferences

import android.content.Context
import androidx.core.content.edit
import io.github.sejoung.panelyink.core.preferences.AppLocale
import io.github.sejoung.panelyink.core.preferences.AppPreferences
import io.github.sejoung.panelyink.core.preferences.AppPreferencesRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * [AppPreferencesRepository] SharedPreferences 구현 — `panely_ink_app_prefs` 파일.
 *
 * Room을 안 쓰는 이유: 글로벌 키-값 한 묶음으로 책별 데이터(Room Position/BookSettings)와
 * 다름. SharedPreferences가 가벼움 + 읽기 빠름.
 */
class SharedPrefsAppPreferencesRepository(
    context: Context,
) : AppPreferencesRepository {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override suspend fun load(): AppPreferences = withContext(Dispatchers.IO) {
        AppPreferences(
            fullRefreshInterval = prefs.getInt(
                KEY_FULL_REFRESH_INTERVAL,
                AppPreferences.DEFAULTS.fullRefreshInterval,
            ),
            invertEnabled = prefs.getBoolean(
                KEY_INVERT_ENABLED,
                AppPreferences.DEFAULTS.invertEnabled,
            ),
            languageTag = prefs.getString(
                AppLocale.KEY_LANGUAGE_TAG,
                AppPreferences.DEFAULTS.languageTag,
            ) ?: AppPreferences.DEFAULTS.languageTag,
        )
    }

    override suspend fun setFullRefreshInterval(value: Int) = withContext(Dispatchers.IO) {
        prefs.edit { putInt(KEY_FULL_REFRESH_INTERVAL, value) }
    }

    override suspend fun setInvertEnabled(value: Boolean) = withContext(Dispatchers.IO) {
        prefs.edit { putBoolean(KEY_INVERT_ENABLED, value) }
    }

    override suspend fun setLanguageTag(value: String) = withContext(Dispatchers.IO) {
        prefs.edit { putString(AppLocale.KEY_LANGUAGE_TAG, value) }
    }

    companion object {
        private const val PREFS_NAME = AppLocale.PREFS_NAME
        private const val KEY_FULL_REFRESH_INTERVAL = "full_refresh_interval"
        private const val KEY_INVERT_ENABLED = "invert_enabled"
    }
}
