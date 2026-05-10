package io.github.sejoung.panelyink.data.db.bookindex

import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.sejoung.panelyink.core.book.BookIdentity
import io.github.sejoung.panelyink.data.db.PanelyDatabase
import io.github.sejoung.panelyink.library.data.IndexedBookEntry
import io.github.sejoung.panelyink.library.model.BookEntry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BookIndexRepositoryTest {

  private lateinit var db: PanelyDatabase
  private lateinit var repo: BookIndexRepository
  private var now: Long = 1000L

  @Before
  fun setUp() {
    db = Room.inMemoryDatabaseBuilder(
      ApplicationProvider.getApplicationContext(),
      PanelyDatabase::class.java,
    )
      .allowMainThreadQueries()
      .build()
    repo = RoomBookIndexRepository(db, clock = { now })
  }

  @After
  fun tearDown() {
    db.close()
  }

  @Test
  fun loadByIdsRestoresBookAndSiblingGroup() = runBlocking {
    val root = Uri.parse("content://root")
    val groupKey = "content://root/folder"
    val a = book(root = root, uri = "content://root/folder/02.cbz", name = "02.cbz")
    val b = book(root = root, uri = "content://root/folder/01.cbz", name = "01.cbz")
    repo.upsertAll(
      listOf(
        IndexedBookEntry(book = a, siblings = listOf(a, b), groupKey = groupKey),
        IndexedBookEntry(book = b, siblings = listOf(a, b), groupKey = groupKey),
      ),
    )

    val loaded = repo.loadByIds(setOf(BookIdentity.fromEntry(a).value))

    assertEquals(1, loaded.size)
    assertEquals(a, loaded.single().book)
    assertEquals(listOf("01.cbz", "02.cbz"), loaded.single().siblings.map { it.displayName })
    assertEquals(groupKey, loaded.single().groupKey)
  }

  @Test
  fun replaceKnownBooksDeletesMissingRows() = runBlocking {
    val root = Uri.parse("content://root")
    val kept = book(root = root, uri = "content://root/kept.cbz", name = "kept.cbz")
    val removed = book(root = root, uri = "content://root/removed.cbz", name = "removed.cbz")
    repo.upsertAll(
      listOf(
        IndexedBookEntry(book = kept, siblings = listOf(kept, removed), groupKey = "content://root"),
        IndexedBookEntry(book = removed, siblings = listOf(kept, removed), groupKey = "content://root"),
      ),
    )

    repo.replaceKnownBooks(
      listOf(IndexedBookEntry(book = kept, siblings = listOf(kept), groupKey = "content://root")),
    )

    assertEquals(1, repo.loadByIds(setOf(BookIdentity.fromEntry(kept).value)).size)
    assertEquals(emptyList<IndexedBookEntry>(), repo.loadByIds(setOf(BookIdentity.fromEntry(removed).value)))
  }

  private fun book(root: Uri, uri: String, name: String): BookEntry =
    BookEntry(
      documentUri = Uri.parse(uri),
      displayName = name,
      sizeBytes = 123L,
      mimeType = "application/zip",
      rootUri = root,
    )
}
