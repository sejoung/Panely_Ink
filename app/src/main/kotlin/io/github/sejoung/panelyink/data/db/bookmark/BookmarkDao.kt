package io.github.sejoung.panelyink.data.db.bookmark

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

    @Query("DELETE FROM bookmark")
    suspend fun deleteAll()

    /** 현재 저장된 모든 book_id (orphan 정리용 — 호출자가 chunk delete를 위해 사용). */
    @Query("SELECT DISTINCT book_id FROM bookmark")
    suspend fun loadDistinctBookIds(): List<String>

    /**
     * 청크 단위 삭제. SQLite host param 한도(999) 때문에 호출자(Repository)가 900개씩
     * 잘라 호출. NOT IN 패턴은 청크별로 잘못 동작하므로(다른 청크의 ID도 NOT IN에 걸림)
     * Repository가 keep set 차집합을 미리 계산해 IN-chunk로 삭제.
     */
    @Query("DELETE FROM bookmark WHERE book_id IN (:bookIds)")
    suspend fun deleteByBookIds(bookIds: List<String>)
}
