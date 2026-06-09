package io.github.sejoung.panelyink.data.db.settings

import io.github.sejoung.panelyink.core.fit.FitMode
import io.github.sejoung.panelyink.core.preferences.ReaderOrientation
import io.github.sejoung.panelyink.core.preferences.ReadingDirection
import io.github.sejoung.panelyink.reader.model.BookSettingsOverrides
import org.junit.Assert.assertEquals
import org.junit.Test

class BookSettingsEntityMappingTest {

    @Test
    fun overridesWithAllFieldsRoundTrip() {
        val overrides = BookSettingsOverrides(
            fitMode = FitMode.FitHeight,
            direction = ReadingDirection.Rtl,
            trimEnabled = false,
            contrast = 1.4f,
            spreadMode = true,
            coverAlone = true,
            orientation = ReaderOrientation.Landscape,
        )
        val entity = overrides.toEntity(bookId = "book", updatedAt = 123L)

        assertEquals("book", entity.bookId)
        assertEquals("FitHeight", entity.fitMode)
        assertEquals("Rtl", entity.direction)
        assertEquals(false, entity.trimEnabled)
        assertEquals(1.4f, entity.contrast)
        assertEquals(true, entity.spreadMode)
        assertEquals(true, entity.coverAlone)
        assertEquals("Landscape", entity.orientation)
        assertEquals(123L, entity.updatedAt)

        assertEquals(overrides, entity.toDomain())
    }

    @Test
    fun emptyOverridesAreAllNullColumns() {
        val entity = BookSettingsOverrides.NONE.toEntity(bookId = "b", updatedAt = 0L)
        assertEquals(null, entity.fitMode)
        assertEquals(null, entity.direction)
        assertEquals(null, entity.trimEnabled)
        assertEquals(null, entity.contrast)
        assertEquals(null, entity.spreadMode)
        assertEquals(null, entity.coverAlone)
        assertEquals(null, entity.orientation)
        assertEquals(BookSettingsOverrides.NONE, entity.toDomain())
    }

    @Test
    fun partialOverridesPreserveNullsForUntouchedFields() {
        // 사용자가 spreadMode만 명시. 나머지는 null → 다음 진입에 전역값 따름.
        val overrides = BookSettingsOverrides(spreadMode = true)
        val entity = overrides.toEntity(bookId = "b", updatedAt = 0L)
        assertEquals(null, entity.fitMode)
        assertEquals(null, entity.direction)
        assertEquals(null, entity.trimEnabled)
        assertEquals(null, entity.contrast)
        assertEquals(true, entity.spreadMode)
        assertEquals(null, entity.orientation)
        assertEquals(overrides, entity.toDomain())
    }

    @Test
    fun unknownEnumValuesFallBackGracefully() {
        val entity = BookSettingsEntity(
            bookId = "b",
            fitMode = "garbage",
            direction = "Tilted",
            trimEnabled = true,
            contrast = 1.0f,
            spreadMode = false,
            coverAlone = null,
            orientation = "Auto",
            updatedAt = 0L,
        )
        val domain = entity.toDomain()
        assertEquals(FitMode.FitScreen, domain.fitMode)
        assertEquals(ReadingDirection.Ltr, domain.direction)
        // 레거시 "Auto" → Portrait 폴백
        assertEquals(ReaderOrientation.Portrait, domain.orientation)
    }
}
