package io.github.sejoung.panelyink.data.db.settings

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface BookSettingsDao {

    @Query("SELECT * FROM book_settings WHERE book_id = :bookId LIMIT 1")
    suspend fun load(bookId: String): BookSettingsEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: BookSettingsEntity)

}
