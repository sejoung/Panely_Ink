package io.github.sejoung.panelyink.data.db.bookindex

import android.net.Uri
import io.github.sejoung.panelyink.core.book.BookIdentity
import io.github.sejoung.panelyink.core.sort.NaturalOrderComparator
import io.github.sejoung.panelyink.data.db.PanelyDatabase
import io.github.sejoung.panelyink.library.data.IndexedBookEntry
import io.github.sejoung.panelyink.library.model.BookEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

interface BookIndexRepository {
  suspend fun upsertAll(indexedBooks: List<IndexedBookEntry>)
  suspend fun loadByIds(bookIds: Set<String>): List<IndexedBookEntry>
  suspend fun replaceKnownBooks(indexedBooks: List<IndexedBookEntry>)
}

class RoomBookIndexRepository(
  private val db: PanelyDatabase,
  private val clock: () -> Long = System::currentTimeMillis,
) : BookIndexRepository {

  private val dao get() = db.bookIndexDao()

  override suspend fun upsertAll(indexedBooks: List<IndexedBookEntry>) = withContext(Dispatchers.IO) {
    if (indexedBooks.isEmpty()) return@withContext
    dao.upsertAll(indexedBooks.toEntities(clock()))
  }

  override suspend fun loadByIds(bookIds: Set<String>): List<IndexedBookEntry> = withContext(Dispatchers.IO) {
    if (bookIds.isEmpty()) return@withContext emptyList()
    val requested = dao.loadAllByIds(bookIds.toList())
    if (requested.isEmpty()) return@withContext emptyList()
    val groupKeys = requested.mapTo(mutableSetOf()) { it.groupKey }
    val siblingsByGroup = dao.loadAllByGroupKeys(groupKeys.toList())
      .groupBy { it.groupKey }
      .mapValues { (_, entities) ->
        entities
          .map { it.toBookEntry() }
          .sortedWith(compareBy(NaturalOrderComparator) { it.displayName })
      }
    requested.map { entity ->
      IndexedBookEntry(
        book = entity.toBookEntry(),
        siblings = siblingsByGroup[entity.groupKey].orEmpty(),
        groupKey = entity.groupKey,
      )
    }
  }

  override suspend fun replaceKnownBooks(indexedBooks: List<IndexedBookEntry>) = withContext(Dispatchers.IO) {
    val ids = indexedBooks.mapTo(mutableSetOf()) { BookIdentity.fromEntry(it.book).value }
    if (ids.isEmpty()) {
      dao.deleteAll()
    } else {
      dao.upsertAll(indexedBooks.toEntities(clock()))
      dao.deleteNotIn(ids.toList())
    }
  }
}

private fun List<IndexedBookEntry>.toEntities(indexedAt: Long): List<BookIndexEntity> =
  map { indexed ->
    val book = indexed.book
    BookIndexEntity(
      bookId = BookIdentity.fromEntry(book).value,
      documentUri = book.documentUri.toString(),
      displayName = book.displayName,
      sizeBytes = book.sizeBytes,
      mimeType = book.mimeType,
      rootUri = book.rootUri.toString(),
      nestedEntryName = book.nestedEntryName,
      groupKey = indexed.groupKey,
      indexedAt = indexedAt,
    )
  }

private fun BookIndexEntity.toBookEntry(): BookEntry =
  BookEntry(
    documentUri = Uri.parse(documentUri),
    displayName = displayName,
    sizeBytes = sizeBytes,
    mimeType = mimeType,
    rootUri = Uri.parse(rootUri),
    nestedEntryName = nestedEntryName,
  )
