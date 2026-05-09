package io.github.sejoung.panelyink.data.db

import io.github.sejoung.panelyink.core.fit.FitMode
import io.github.sejoung.panelyink.reader.BookSettings
import io.github.sejoung.panelyink.reader.ReadingDirection
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
        ).toEntity(bookId = "book", updatedAt = 123L)

        assertEquals("book", entity.bookId)
        assertEquals("FitHeight", entity.fitMode)
        assertEquals("Rtl", entity.direction)
        assertEquals(false, entity.trimEnabled)
        assertEquals(1.4f, entity.contrast)
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
            updatedAt = 456L,
        )

        assertEquals(
            BookSettings(
                fitMode = FitMode.Zoom(1.25f),
                direction = ReadingDirection.Ltr,
                trimEnabled = true,
                contrast = 0.85f,
            ),
            entity.toDomain(),
        )
    }
}
