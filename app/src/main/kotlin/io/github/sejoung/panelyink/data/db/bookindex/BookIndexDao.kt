package io.github.sejoung.panelyink.data.db.bookindex

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface BookIndexDao {

  @Query("SELECT * FROM book_index WHERE book_id IN (:bookIds)")
  suspend fun loadAllByIds(bookIds: List<String>): List<BookIndexEntity>

  @Query("SELECT * FROM book_index WHERE group_key IN (:groupKeys) ORDER BY display_name ASC")
  suspend fun loadAllByGroupKeys(groupKeys: List<String>): List<BookIndexEntity>

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun upsertAll(entities: List<BookIndexEntity>)

  /** orphan 정리용 — 호출자가 keep set 차집합을 IN-chunk로 삭제하는 데 사용. */
  @Query("SELECT book_id FROM book_index")
  suspend fun loadAllBookIds(): List<String>

  /**
   * 청크 단위 삭제. SQLite host param 한도(999) 때문에 Repository가 900개씩 잘라 호출.
   * NOT IN 패턴은 청크 분할이 불가능하므로 keep set 차집합 후 IN-chunk로 삭제.
   */
  @Query("DELETE FROM book_index WHERE book_id IN (:bookIds)")
  suspend fun deleteByBookIds(bookIds: List<String>)

  @Query("DELETE FROM book_index")
  suspend fun deleteAll()
}
