package io.github.sejoung.panelyink.data.db.bookmark

import io.github.sejoung.panelyink.data.db.PanelyDatabase
import io.github.sejoung.panelyink.data.db.forEachChunk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

interface BookmarkRepository {
    suspend fun loadPages(bookId: String): List<Int>
    suspend fun loadAll(): List<BookBookmark>
    suspend fun isBookmarked(bookId: String, pageIndex: Int): Boolean
    suspend fun add(bookId: String, pageIndex: Int)
    suspend fun remove(bookId: String, pageIndex: Int)
    suspend fun removeOrphans(existingBookIds: Set<String>)
}

data class BookBookmark(
    val bookId: String,
    val pageIndex: Int,
    val createdAt: Long,
)

class RoomBookmarkRepository(
    private val db: PanelyDatabase,
    private val clock: () -> Long = System::currentTimeMillis,
) : BookmarkRepository {

    private val dao get() = db.bookmarkDao()

    override suspend fun loadPages(bookId: String): List<Int> = withContext(Dispatchers.IO) {
        dao.loadForBook(bookId).map { it.pageIndex }
    }

    override suspend fun loadAll(): List<BookBookmark> = withContext(Dispatchers.IO) {
        dao.loadAll().map { it.toDomain() }
    }

    override suspend fun isBookmarked(bookId: String, pageIndex: Int): Boolean =
        withContext(Dispatchers.IO) {
            dao.exists(bookId, pageIndex)
        }

    override suspend fun add(bookId: String, pageIndex: Int) = withContext(Dispatchers.IO) {
        dao.upsert(
            BookmarkEntity(
                bookId = bookId,
                pageIndex = pageIndex,
                createdAt = clock(),
            ),
        )
    }

    override suspend fun remove(bookId: String, pageIndex: Int) = withContext(Dispatchers.IO) {
        dao.delete(bookId, pageIndex)
    }

    override suspend fun removeOrphans(existingBookIds: Set<String>) = withContext(Dispatchers.IO) {
        if (existingBookIds.isEmpty()) {
            dao.deleteAll()
            return@withContext
        }
        // 1000+ 책 라이브러리 대응: NOT IN(:list)는 host param 한도(999)에 걸리고 청크
        // 분할로 직접 모방할 수도 없다(각 청크에서 다른 청크 ID가 NOT IN에 잡혀 잘못 삭제).
        // 저장된 book_id 전체를 가져와 keep set 차집합을 계산 후 IN-chunk로 삭제.
        val stored = dao.loadDistinctBookIds()
        val orphans = stored.filterNot { it in existingBookIds }
        orphans.forEachChunk { chunk -> dao.deleteByBookIds(chunk) }
    }
}

private fun BookmarkEntity.toDomain(): BookBookmark = BookBookmark(
    bookId = bookId,
    pageIndex = pageIndex,
    createdAt = createdAt,
)
