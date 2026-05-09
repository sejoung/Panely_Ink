package io.github.sejoung.panelyink.data.db

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

interface BookmarkRepository {
    suspend fun loadPages(bookId: String): List<Int>
    suspend fun isBookmarked(bookId: String, pageIndex: Int): Boolean
    suspend fun add(bookId: String, pageIndex: Int)
    suspend fun remove(bookId: String, pageIndex: Int)
}

class RoomBookmarkRepository(
    private val db: PanelyDatabase,
    private val clock: () -> Long = System::currentTimeMillis,
) : BookmarkRepository {

    private val dao get() = db.bookmarkDao()

    override suspend fun loadPages(bookId: String): List<Int> = withContext(Dispatchers.IO) {
        dao.loadForBook(bookId).map { it.pageIndex }
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
}
