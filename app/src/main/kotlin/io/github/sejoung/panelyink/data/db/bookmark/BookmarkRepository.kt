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

    /**
     * [existingBookIds]에 없는 책의 북마크를 삭제. **빈 집합이면 no-op** — 실패한 스캔 결과가
     * 그대로 넘어와 전체 북마크가 지워지는 사고를 막기 위한 방어선.
     */
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
        // 빈 keep set은 "책이 하나도 없음"이 아니라 "스캔 실패"(SD 언마운트, SAF 권한 회수 →
        // DocumentsProvider가 null cursor/예외)일 가능성이 훨씬 높다. 이때 전체 삭제하면
        // 사용자의 모든 북마크가 복구 불가능하게 사라지므로 아무것도 지우지 않는다.
        // 의도적인 전체 삭제는 "전체 초기화"(AppDataResetter)만 담당.
        if (existingBookIds.isEmpty()) return@withContext
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
