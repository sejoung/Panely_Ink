package io.github.sejoung.panelyink.core.preferences

import io.github.sejoung.panelyink.reader.ReaderViewModel
import io.github.sejoung.panelyink.reader.model.ReadingDirection
import org.junit.Assert.assertEquals
import org.junit.Test

class AppPreferencesTest {

    @Test
    fun defaultsUseSystemLanguageAndReaderFullRefreshDefault() {
        assertEquals(ReadingDirection.Ltr, AppPreferences.DEFAULTS.defaultReadingDirection)
        assertEquals(AppLanguage.System.tag, AppPreferences.DEFAULTS.languageTag)
        assertEquals(
            ReaderViewModel.FULL_REFRESH_INTERVAL_DEFAULT,
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
