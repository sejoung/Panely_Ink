package io.github.sejoung.panelyink.data.db.bookindex

import android.net.Uri
import io.github.sejoung.panelyink.core.book.BookRef
import io.github.sejoung.panelyink.core.book.IndexedBookRef
import io.github.sejoung.panelyink.core.sort.NaturalOrderComparator
import io.github.sejoung.panelyink.data.db.PanelyDatabase
import io.github.sejoung.panelyink.data.db.flatMapInChunks
import io.github.sejoung.panelyink.data.db.forEachChunk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

interface BookIndexRepository {
  suspend fun upsertAll(indexedBooks: List<IndexedBookRef>)
  suspend fun loadByIds(bookIds: Set<String>): List<IndexedBookRef>

  /**
   * 인덱스를 [indexedBooks]로 교체(upsert + 목록에 없는 행 삭제). **빈 목록이면 no-op** —
   * 호출자는 "완전한 스캔" 결과만 넘겨야 하며, 부분 실패한 스캔은 [upsertAll]만 사용.
   */
  suspend fun replaceKnownBooks(indexedBooks: List<IndexedBookRef>)
}

class RoomBookIndexRepository(
  private val db: PanelyDatabase,
  private val clock: () -> Long = System::currentTimeMillis,
) : BookIndexRepository {

  private val dao get() = db.bookIndexDao()

  override suspend fun upsertAll(indexedBooks: List<IndexedBookRef>) = withContext(Dispatchers.IO) {
    if (indexedBooks.isEmpty()) return@withContext
    // upsertAll도 큰 리스트면 SQLite bind 한도에 걸릴 수 있어 청크로.
    indexedBooks.toEntities(clock()).forEachChunk { chunk -> dao.upsertAll(chunk) }
  }

  override suspend fun loadByIds(bookIds: Set<String>): List<IndexedBookRef> = withContext(Dispatchers.IO) {
    if (bookIds.isEmpty()) return@withContext emptyList()
    val requested = bookIds.toList()
      .flatMapInChunks { chunk -> dao.loadAllByIds(chunk) }
    if (requested.isEmpty()) return@withContext emptyList()
    val groupKeys = requested.mapTo(mutableSetOf()) { it.groupKey }.toList()
    val siblingsByGroup = groupKeys
      .flatMapInChunks { chunk -> dao.loadAllByGroupKeys(chunk) }
      .groupBy { it.groupKey }
      .mapValues { (_, entities) ->
        entities
          .map { it.toBookRef() }
          .sortedWith(compareBy(NaturalOrderComparator) { it.displayName })
      }
    requested.map { entity ->
      IndexedBookRef(
        book = entity.toBookRef(),
        siblings = siblingsByGroup[entity.groupKey].orEmpty(),
        groupKey = entity.groupKey,
      )
    }
  }

  override suspend fun replaceKnownBooks(indexedBooks: List<IndexedBookRef>) = withContext(Dispatchers.IO) {
    val ids = indexedBooks.mapTo(mutableSetOf()) { it.book.bookId.value }
    // 빈 목록은 "라이브러리가 비었음"보다 "스캔 실패"(SD 언마운트/권한 회수)일 가능성이 높다.
    // 전체 삭제하지 않고 no-op — 의도적인 전체 삭제는 "전체 초기화"(AppDataResetter)만 담당.
    if (ids.isEmpty()) return@withContext
    indexedBooks.toEntities(clock()).forEachChunk { chunk -> dao.upsertAll(chunk) }
    // NOT IN 청크 분할 불가 — 저장된 ID 전체를 fetch해 차집합을 IN-chunk로 삭제.
    val stored = dao.loadAllBookIds()
    val orphans = stored.filterNot { it in ids }
    orphans.forEachChunk { chunk -> dao.deleteByBookIds(chunk) }
  }
}

private fun List<IndexedBookRef>.toEntities(indexedAt: Long): List<BookIndexEntity> =
  map { indexed ->
    val book = indexed.book
    BookIndexEntity(
      bookId = book.bookId.value,
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

private fun BookIndexEntity.toBookRef(): BookRef =
  BookRef(
    documentUri = Uri.parse(documentUri),
    displayName = displayName,
    sizeBytes = sizeBytes,
    mimeType = mimeType,
    rootUri = Uri.parse(rootUri),
    nestedEntryName = nestedEntryName,
  )
