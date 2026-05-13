package io.github.sejoung.panelyink.core.preferences

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
