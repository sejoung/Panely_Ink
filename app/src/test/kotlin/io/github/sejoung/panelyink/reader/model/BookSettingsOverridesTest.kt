package io.github.sejoung.panelyink.reader.model

import io.github.sejoung.panelyink.core.fit.FitMode
import io.github.sejoung.panelyink.core.preferences.AppPreferences
import io.github.sejoung.panelyink.core.preferences.ReaderOrientation
import io.github.sejoung.panelyink.core.preferences.ReadingDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BookSettingsOverridesTest {

    private val appPrefs = AppPreferences.DEFAULTS.copy(
        defaultReadingDirection = ReadingDirection.Rtl,
        defaultSpreadMode = true,
        defaultOrientation = ReaderOrientation.Landscape,
    )

    @Test
    fun noneIsEmpty() {
        assertTrue(BookSettingsOverrides.NONE.isEmpty)
    }

    @Test
    fun anySingleFieldMakesNonEmpty() {
        assertFalse(BookSettingsOverrides(spreadMode = false).isEmpty)
        assertFalse(BookSettingsOverrides(orientation = ReaderOrientation.Portrait).isEmpty)
        assertFalse(BookSettingsOverrides(direction = ReadingDirection.Ltr).isEmpty)
        assertFalse(BookSettingsOverrides(trimEnabled = true).isEmpty)
        assertFalse(BookSettingsOverrides(contrast = 1.5f).isEmpty)
        assertFalse(BookSettingsOverrides(fitMode = FitMode.FitWidth).isEmpty)
    }

    @Test
    fun resolveEmptyOverridesUsesAllGlobals() {
        val resolved = BookSettingsOverrides.NONE.resolve(appPrefs)
        assertEquals(ReadingDirection.Rtl, resolved.direction)
        assertEquals(true, resolved.spreadMode)
        assertEquals(ReaderOrientation.Landscape, resolved.orientation)
        // fitMode/trim/contrast는 appPrefs에 필드 없음 → fallback DEFAULTS
        assertEquals(BookSettings.DEFAULTS.fitMode, resolved.fitMode)
        assertEquals(BookSettings.DEFAULTS.trimEnabled, resolved.trimEnabled)
        assertEquals(BookSettings.DEFAULTS.contrast, resolved.contrast)
    }

    @Test
    fun resolvePartialOverrideMixesGlobalAndExplicit() {
        // 사용자가 spreadMode만 false로 명시 — 다른 필드는 전역값을 따라야 한다.
        val overrides = BookSettingsOverrides(spreadMode = false)
        val resolved = overrides.resolve(appPrefs)
        assertEquals(false, resolved.spreadMode) // 명시 override
        assertEquals(ReadingDirection.Rtl, resolved.direction) // 전역
        assertEquals(ReaderOrientation.Landscape, resolved.orientation) // 전역
    }

    @Test
    fun resolveFullOverridesIgnoresGlobals() {
        val overrides = BookSettingsOverrides(
            fitMode = FitMode.FitWidth,
            direction = ReadingDirection.Ltr,
            trimEnabled = false,
            contrast = 0.8f,
            spreadMode = false,
            orientation = ReaderOrientation.Portrait,
        )
        val resolved = overrides.resolve(appPrefs)
        assertEquals(FitMode.FitWidth, resolved.fitMode)
        assertEquals(ReadingDirection.Ltr, resolved.direction)
        assertEquals(false, resolved.trimEnabled)
        assertEquals(0.8f, resolved.contrast)
        assertEquals(false, resolved.spreadMode)
        assertEquals(ReaderOrientation.Portrait, resolved.orientation)
    }
}
