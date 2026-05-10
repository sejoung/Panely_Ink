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

  @Query("DELETE FROM book_index WHERE book_id NOT IN (:bookIds)")
  suspend fun deleteNotIn(bookIds: List<String>)

  @Query("DELETE FROM book_index")
  suspend fun deleteAll()
}
