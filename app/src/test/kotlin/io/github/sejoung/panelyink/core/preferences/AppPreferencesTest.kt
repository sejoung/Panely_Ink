package io.github.sejoung.panelyink.core.preferences

import io.github.sejoung.panelyink.core.fit.FitMode
import io.github.sejoung.panelyink.core.render.ContrastMatrix
import org.junit.Assert.assertEquals
import org.junit.Test

class AppPreferencesTest {

    @Test
    fun defaultsUseSystemLanguageAndFullRefreshDefault() {
        assertEquals(ReadingDirection.Ltr, AppPreferences.DEFAULTS.defaultReadingDirection)
        assertEquals(AppLanguage.System.tag, AppPreferences.DEFAULTS.languageTag)
        assertEquals(
            DEFAULT_FULL_REFRESH_INTERVAL,
            AppPreferences.DEFAULTS.fullRefreshInterval,
        )
        assertEquals(false, AppPreferences.DEFAULTS.invertEnabled)
        // v1.1: 새 책 첫 진입 시 v1.0과 동일한 시각 동작(단쪽 + 세로) + 트리밍 OFF (spread+trim 정렬 이슈 회피)
        assertEquals(false, AppPreferences.DEFAULTS.defaultSpreadMode)
        assertEquals(ReaderOrientation.Portrait, AppPreferences.DEFAULTS.defaultOrientation)
        assertEquals(false, AppPreferences.DEFAULTS.defaultTrimEnabled)
        assertEquals(FitMode.FitScreen, AppPreferences.DEFAULTS.defaultFitMode)
        assertEquals(ContrastMatrix.IDENTITY, AppPreferences.DEFAULTS.defaultContrast)
    }

    @Test
    fun languageFromTagMapsKnownValues() {
        assertEquals(AppLanguage.English, AppLanguage.fromTag("en"))
        assertEquals(AppLanguage.Korean, AppLanguage.fromTag("ko"))
        assertEquals(AppLanguage.System, AppLanguage.fromTag("system"))
    }

    @Test
    fun languageFromTagFallsBackToEnglish() {
        assertEquals(AppLanguage.English, AppLanguage.fromTag(null))
        assertEquals(AppLanguage.English, AppLanguage.fromTag(""))
        assertEquals(AppLanguage.English, AppLanguage.fromTag("ja"))
    }
}
