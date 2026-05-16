package io.github.sejoung.panelyink.data.preferences

import android.content.Context
import androidx.core.content.edit
import io.github.sejoung.panelyink.core.fit.FitMode
import io.github.sejoung.panelyink.core.preferences.AppLocale
import io.github.sejoung.panelyink.core.preferences.AppPreferences
import io.github.sejoung.panelyink.core.preferences.AppPreferencesRepository
import io.github.sejoung.panelyink.core.preferences.ReaderOrientation
import io.github.sejoung.panelyink.core.preferences.ReadingDirection
import io.github.sejoung.panelyink.core.preferences.deserializeOrientation
import io.github.sejoung.panelyink.reader.model.deserializeDirection
import io.github.sejoung.panelyink.reader.model.deserializeFitMode
import io.github.sejoung.panelyink.reader.model.serializeFitMode
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
            defaultReadingDirection = deserializeDirection(
                prefs.getString(
                    KEY_DEFAULT_READING_DIRECTION,
                    AppPreferences.DEFAULTS.defaultReadingDirection.name,
                ) ?: AppPreferences.DEFAULTS.defaultReadingDirection.name,
            ),
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
            defaultSpreadMode = prefs.getBoolean(
                KEY_DEFAULT_SPREAD_MODE,
                AppPreferences.DEFAULTS.defaultSpreadMode,
            ),
            defaultOrientation = deserializeOrientation(
                prefs.getString(
                    KEY_DEFAULT_ORIENTATION,
                    AppPreferences.DEFAULTS.defaultOrientation.name,
                ) ?: AppPreferences.DEFAULTS.defaultOrientation.name,
            ),
            defaultTrimEnabled = prefs.getBoolean(
                KEY_DEFAULT_TRIM_ENABLED,
                AppPreferences.DEFAULTS.defaultTrimEnabled,
            ),
            defaultFitMode = deserializeFitMode(
                prefs.getString(
                    KEY_DEFAULT_FIT_MODE,
                    serializeFitMode(AppPreferences.DEFAULTS.defaultFitMode),
                ) ?: serializeFitMode(AppPreferences.DEFAULTS.defaultFitMode),
            ),
            defaultContrast = prefs.getFloat(
                KEY_DEFAULT_CONTRAST,
                AppPreferences.DEFAULTS.defaultContrast,
            ),
        )
    }

    override suspend fun setDefaultReadingDirection(value: ReadingDirection) = withContext(Dispatchers.IO) {
        prefs.edit { putString(KEY_DEFAULT_READING_DIRECTION, value.name) }
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

    override suspend fun setDefaultSpreadMode(value: Boolean) = withContext(Dispatchers.IO) {
        prefs.edit { putBoolean(KEY_DEFAULT_SPREAD_MODE, value) }
    }

    override suspend fun setDefaultOrientation(value: ReaderOrientation) = withContext(Dispatchers.IO) {
        prefs.edit { putString(KEY_DEFAULT_ORIENTATION, value.name) }
    }

    override suspend fun setDefaultTrimEnabled(value: Boolean) = withContext(Dispatchers.IO) {
        prefs.edit { putBoolean(KEY_DEFAULT_TRIM_ENABLED, value) }
    }

    override suspend fun setDefaultFitMode(value: FitMode) = withContext(Dispatchers.IO) {
        prefs.edit { putString(KEY_DEFAULT_FIT_MODE, serializeFitMode(value)) }
    }

    override suspend fun setDefaultContrast(value: Float) = withContext(Dispatchers.IO) {
        prefs.edit { putFloat(KEY_DEFAULT_CONTRAST, value) }
    }

    companion object {
        private const val PREFS_NAME = AppLocale.PREFS_NAME
        private const val KEY_DEFAULT_READING_DIRECTION = "default_reading_direction"
        private const val KEY_FULL_REFRESH_INTERVAL = "full_refresh_interval"
        private const val KEY_INVERT_ENABLED = "invert_enabled"
        private const val KEY_DEFAULT_SPREAD_MODE = "default_spread_mode"
        private const val KEY_DEFAULT_ORIENTATION = "default_orientation"
        private const val KEY_DEFAULT_TRIM_ENABLED = "default_trim_enabled"
        private const val KEY_DEFAULT_FIT_MODE = "default_fit_mode"
        private const val KEY_DEFAULT_CONTRAST = "default_contrast"
    }
}
