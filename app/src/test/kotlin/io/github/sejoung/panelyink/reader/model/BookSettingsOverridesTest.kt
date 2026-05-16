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
        defaultTrimEnabled = true,
        defaultFitMode = FitMode.FitWidth,
        defaultContrast = 1.4f,
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
        // v1.1: 6필드 전부 appPrefs에서 합성 — 하드코드 fallback 미사용.
        val resolved = BookSettingsOverrides.NONE.resolve(appPrefs)
        assertEquals(ReadingDirection.Rtl, resolved.direction)
        assertEquals(true, resolved.spreadMode)
        assertEquals(ReaderOrientation.Landscape, resolved.orientation)
        assertEquals(true, resolved.trimEnabled)
        assertEquals(FitMode.FitWidth, resolved.fitMode)
        assertEquals(1.4f, resolved.contrast)
    }

    @Test
    fun resolveTrimFollowsAppPrefsWhenNoOverride() {
        val offPrefs = appPrefs.copy(defaultTrimEnabled = false)
        val resolved = BookSettingsOverrides.NONE.resolve(offPrefs)
        assertEquals(false, resolved.trimEnabled)
    }

    @Test
    fun resolveTrimOverrideBeatsAppPrefs() {
        // 사용자가 책별 trim=false 명시 → 전역이 true여도 false 유지
        val overrides = BookSettingsOverrides(trimEnabled = false)
        val resolved = overrides.resolve(appPrefs.copy(defaultTrimEnabled = true))
        assertEquals(false, resolved.trimEnabled)
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
    fun resolveFitModeFollowsAppPrefs() {
        val resolved = BookSettingsOverrides.NONE.resolve(appPrefs.copy(defaultFitMode = FitMode.FitHeight))
        assertEquals(FitMode.FitHeight, resolved.fitMode)
    }

    @Test
    fun resolveContrastFollowsAppPrefs() {
        val resolved = BookSettingsOverrides.NONE.resolve(appPrefs.copy(defaultContrast = 0.8f))
        assertEquals(0.8f, resolved.contrast)
    }

    @Test
    fun resolveFitModeOverrideBeatsAppPrefs() {
        // 사용자가 책별로 Zoom 선택 — 전역 FitWidth와 무관하게 Zoom 유지
        val overrides = BookSettingsOverrides(fitMode = FitMode.Zoom(1.5f))
        val resolved = overrides.resolve(appPrefs.copy(defaultFitMode = FitMode.FitWidth))
        assertEquals(FitMode.Zoom(1.5f), resolved.fitMode)
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
