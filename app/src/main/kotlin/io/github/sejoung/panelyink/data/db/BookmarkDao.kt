package io.github.sejoung.panelyink.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface BookmarkDao {

    @Query("SELECT * FROM bookmark WHERE book_id = :bookId ORDER BY page_index ASC")
    suspend fun loadForBook(bookId: String): List<BookmarkEntity>

    @Query("SELECT * FROM bookmark ORDER BY created_at DESC, book_id ASC, page_index ASC")
    suspend fun loadAll(): List<BookmarkEntity>

    @Query(
        """
        SELECT EXISTS(
            SELECT 1 FROM bookmark
            WHERE book_id = :bookId AND page_index = :pageIndex
        )
        """,
    )
    suspend fun exists(bookId: String, pageIndex: Int): Boolean

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: BookmarkEntity)

    @Query("DELETE FROM bookmark WHERE book_id = :bookId AND page_index = :pageIndex")
    suspend fun delete(bookId: String, pageIndex: Int)

    @Query("DELETE FROM bookmark WHERE book_id = :bookId")
    suspend fun deleteForBook(bookId: String)

    @Query("DELETE FROM bookmark")
    suspend fun deleteAll()

    @Query("DELETE FROM bookmark WHERE book_id NOT IN (:bookIds)")
    suspend fun deleteNotIn(bookIds: List<String>)
}
