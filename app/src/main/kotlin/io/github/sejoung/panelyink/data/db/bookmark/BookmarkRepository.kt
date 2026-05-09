package io.github.sejoung.panelyink.data.db.bookmark

import io.github.sejoung.panelyink.data.db.PanelyDatabase
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
        } else {
            dao.deleteNotIn(existingBookIds.toList())
        }
    }
}

private fun BookmarkEntity.toDomain(): BookBookmark = BookBookmark(
    bookId = bookId,
    pageIndex = pageIndex,
    createdAt = createdAt,
)
