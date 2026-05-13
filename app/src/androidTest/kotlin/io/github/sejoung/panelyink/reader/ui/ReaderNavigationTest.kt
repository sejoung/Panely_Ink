package io.github.sejoung.panelyink.reader.ui

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.sejoung.panelyink.core.fit.FitMode
import io.github.sejoung.panelyink.library.model.BookEntry
import io.github.sejoung.panelyink.reader.ReaderState
import io.github.sejoung.panelyink.core.preferences.ReadingDirection
import io.github.sejoung.panelyink.reader.model.SeriesContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReaderNavigationTest {

    @Test
    fun previousBookOnlyAtFirstPage() {
        val context = seriesContext(current = 1)

        assertEquals(
            context.previousBook,
            previousBookForBoundary(state(currentPage = 0), context),
        )
        assertNull(previousBookForBoundary(state(currentPage = 1), context))
    }

    @Test
    fun nextBookOnlyAtLastPage() {
        val context = seriesContext(current = 1)

        assertNull(nextBookForBoundary(state(currentPage = 8), pageCount = 10, context))
        assertEquals(
            context.nextBook,
            nextBookForBoundary(state(currentPage = 9), pageCount = 10, context),
        )
    }

    @Test
    fun standaloneHasNoAdjacentBooks() {
        val book = book(0)
        val context = SeriesContext.standalone(book)

        assertNull(previousBookForBoundary(state(currentPage = 0), context))
        assertNull(nextBookForBoundary(state(currentPage = 9), pageCount = 10, context))
    }

    @Test
    fun stateToBookSettingsCopiesBookScopedFieldsOnly() {
        val settings = state(
            currentPage = 3,
            direction = ReadingDirection.Rtl,
            fitMode = FitMode.FitWidth,
            trimEnabled = false,
            contrast = 1.25f,
        ).toBookSettings()

        assertEquals(FitMode.FitWidth, settings.fitMode)
        assertEquals(ReadingDirection.Rtl, settings.direction)
        assertEquals(false, settings.trimEnabled)
        assertEquals(1.25f, settings.contrast)
    }

    private fun seriesContext(current: Int): SeriesContext {
        val books = List(3) { book(it) }
        return SeriesContext(current = books[current], siblings = books)
    }

    private fun book(index: Int): BookEntry = BookEntry(
        documentUri = Uri.parse("content://books/$index.cbz"),
        displayName = "Book $index.cbz",
        sizeBytes = 1024L,
        mimeType = "application/zip",
        rootUri = Uri.parse("content://books"),
    )

    private fun state(
        currentPage: Int,
        direction: ReadingDirection = ReadingDirection.Ltr,
        fitMode: FitMode = FitMode.FitScreen,
        trimEnabled: Boolean = true,
        contrast: Float = 1.0f,
    ): ReaderState = ReaderState(
        currentPage = currentPage,
        direction = direction,
        fitMode = fitMode,
        preloadWindow = currentPage..currentPage,
        trimEnabled = trimEnabled,
        contrast = contrast,
    )
}
