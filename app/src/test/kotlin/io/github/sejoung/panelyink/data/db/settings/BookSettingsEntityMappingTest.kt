package io.github.sejoung.panelyink.data.db.settings

import io.github.sejoung.panelyink.core.fit.FitMode
import io.github.sejoung.panelyink.core.preferences.ReaderOrientation
import io.github.sejoung.panelyink.core.preferences.ReadingDirection
import io.github.sejoung.panelyink.reader.model.BookSettings
import org.junit.Assert.assertEquals
import org.junit.Test

class BookSettingsEntityMappingTest {

    @Test
    fun domainToEntityUsesOnlyBookScopedColumns() {
        val entity = BookSettings(
            fitMode = FitMode.FitHeight,
            direction = ReadingDirection.Rtl,
            trimEnabled = false,
            contrast = 1.4f,
            spreadMode = true,
            orientation = ReaderOrientation.Landscape,
        ).toEntity(bookId = "book", updatedAt = 123L)

        assertEquals("book", entity.bookId)
        assertEquals("FitHeight", entity.fitMode)
        assertEquals("Rtl", entity.direction)
        assertEquals(false, entity.trimEnabled)
        assertEquals(1.4f, entity.contrast)
        assertEquals(true, entity.spreadMode)
        assertEquals("Landscape", entity.orientation)
        assertEquals(123L, entity.updatedAt)
    }

    @Test
    fun entityToDomainRoundTrip() {
        val entity = BookSettingsEntity(
            bookId = "book",
            fitMode = "Zoom:1.25",
            direction = "Ltr",
            trimEnabled = true,
            contrast = 0.85f,
            spreadMode = true,
            orientation = "Portrait",
            updatedAt = 456L,
        )

        assertEquals(
            BookSettings(
                fitMode = FitMode.Zoom(1.25f),
                direction = ReadingDirection.Ltr,
                trimEnabled = true,
                contrast = 0.85f,
                spreadMode = true,
                orientation = ReaderOrientation.Portrait,
            ),
            entity.toDomain(),
        )
    }

    @Test
    fun entitySpreadModeFalseRoundTrip() {
        val entity = BookSettingsEntity(
            bookId = "b",
            fitMode = "FitScreen",
            direction = "Ltr",
            trimEnabled = true,
            contrast = 1.0f,
            spreadMode = false,
            orientation = "Auto",
            updatedAt = 0L,
        )
        assertEquals(false, entity.toDomain().spreadMode)
        assertEquals(ReaderOrientation.Auto, entity.toDomain().orientation)
    }

    @Test
    fun unknownOrientationFallsBackToAuto() {
        val entity = BookSettingsEntity(
            bookId = "b",
            fitMode = "FitScreen",
            direction = "Ltr",
            trimEnabled = true,
            contrast = 1.0f,
            spreadMode = false,
            orientation = "Tilted",
            updatedAt = 0L,
        )
        assertEquals(ReaderOrientation.Auto, entity.toDomain().orientation)
    }
}
