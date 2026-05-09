package io.github.sejoung.panelyink.library.data

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.sejoung.panelyink.core.position.BookProgress
import io.github.sejoung.panelyink.core.position.PositionKey
import io.github.sejoung.panelyink.core.position.PositionRepository
import io.github.sejoung.panelyink.data.db.cover.CoverMeta
import io.github.sejoung.panelyink.data.db.cover.CoverMetaRepository
import io.github.sejoung.panelyink.data.db.cover.CoverStatus
import io.github.sejoung.panelyink.library.model.BookEntry
import io.github.sejoung.panelyink.library.model.FolderEntry
import io.github.sejoung.panelyink.library.model.SortMode
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LibraryEntryDataTest {

  @Test
  fun nameSortKeepsFoldersBeforeBooksAndExistingBookOrder() = runTest {
    val entries = listOf(
      book("Book 10.cbz"),
      folder("Series"),
      book("Book 2.cbz"),
    )

    val sorted = sortLibraryEntries(entries, SortMode.Name, FakePositionRepository())

    assertEquals(
      listOf("Series", "Book 10.cbz", "Book 2.cbz"),
      sorted.map { it.displayName },
    )
  }

  @Test
  fun lastOpenedSortsBooksByTimestampDescending() = runTest {
    val old = book("Old.cbz")
    val new = book("New.cbz")
    val never = book("Never.cbz")
    val repo = FakePositionRepository(
      updatedAt = mapOf(
        bookId(old) to 10L,
        bookId(new) to 30L,
      ),
    )

    val sorted = sortLibraryEntries(
      entries = listOf(old, never, new),
      mode = SortMode.LastOpened,
      positionRepo = repo,
    )

    assertEquals(listOf("New.cbz", "Old.cbz", "Never.cbz"), sorted.map { it.displayName })
  }

  @Test
  fun prefetchBookDataFiltersUnknownProgressAndMapsCoverStatus() = runTest {
    val known = book("Known.cbz")
    val unknown = book("Unknown.cbz")
    val knownId = bookId(known)
    val unknownId = bookId(unknown)

    val data = prefetchBookData(
      entries = listOf(folder("Folder"), known, unknown),
      positionRepo = FakePositionRepository(
        progress = mapOf(
          knownId to BookProgress(pageIndex = 9, pageCount = 100),
          unknownId to BookProgress(pageIndex = 0, pageCount = 0),
        ),
      ),
      coverMetaRepo = FakeCoverMetaRepository(
        meta = mapOf(
          knownId to CoverMeta(knownId, CoverStatus.OK, 0, 100L),
          unknownId to CoverMeta(unknownId, CoverStatus.FAILED, 0, 200L),
        ),
      ),
    )

    assertEquals(mapOf(knownId to BookProgress(9, 100)), data.progress)
    assertEquals(
      mapOf(knownId to CoverStatus.OK, unknownId to CoverStatus.FAILED),
      data.coverStatus,
    )
  }

  private fun book(name: String): BookEntry = BookEntry(
    documentUri = Uri.parse("content://root/$name"),
    displayName = name,
    sizeBytes = 1024L,
    mimeType = "application/zip",
    rootUri = Uri.parse("content://root"),
  )

  private fun folder(name: String): FolderEntry = FolderEntry(
    documentUri = Uri.parse("content://root/$name"),
    displayName = name,
    rootUri = Uri.parse("content://root"),
    isRoot = false,
  )

  private fun bookId(book: BookEntry): String = PositionKey.bookIdFromUri(book.bookIdSource)
}

private class FakePositionRepository(
  private val updatedAt: Map<String, Long> = emptyMap(),
  private val progress: Map<String, BookProgress> = emptyMap(),
) : PositionRepository {
  override suspend fun load(bookId: String): BookProgress? = progress[bookId]

  override suspend fun save(bookId: String, pageIndex: Int, pageCount: Int) = Unit

  override suspend fun loadUpdatedAtMap(bookIds: List<String>): Map<String, Long> =
    updatedAt.filterKeys { it in bookIds }

  override suspend fun loadProgressMap(bookIds: List<String>): Map<String, BookProgress> =
    progress.filterKeys { it in bookIds }
}

private class FakeCoverMetaRepository(
  private val meta: Map<String, CoverMeta> = emptyMap(),
) : CoverMetaRepository {
  override suspend fun load(bookId: String): CoverMeta? = meta[bookId]

  override suspend fun save(
    bookId: String,
    status: CoverStatus,
    sourcePageIndex: Int,
  ) = Unit

  override suspend fun delete(bookId: String) = Unit

  override suspend fun loadOkOrderedByLru(): List<CoverMeta> =
    meta.values.filter { it.status == CoverStatus.OK }.sortedBy { it.extractedAt }

  override suspend fun deleteAll() = Unit

  override suspend fun loadMap(bookIds: List<String>): Map<String, CoverMeta> =
    meta.filterKeys { it in bookIds }
}
