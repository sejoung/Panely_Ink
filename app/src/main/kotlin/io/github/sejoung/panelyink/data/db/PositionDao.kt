package io.github.sejoung.panelyink.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * [PositionEntity] 접근 — Room 컴파일러가 구현 생성.
 *
 * 메서드 모두 `suspend` 또는 sync. 호출자(`RoomPositionRepository`)가 IO
 * 디스패처에서 호출 — 페이지 변경마다 호출되므로 INSERT/REPLACE는 가벼워야 한다.
 */
@Dao
interface PositionDao {

    @Query("SELECT * FROM position WHERE book_id = :bookId LIMIT 1")
    suspend fun load(bookId: String): PositionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: PositionEntity)

    @Query("DELETE FROM position WHERE book_id = :bookId")
    suspend fun delete(bookId: String)

    /** 마이그레이션 1회 실행용 — 빈 테이블에 일괄 삽입. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(entities: List<PositionEntity>)

    @Query("SELECT COUNT(*) FROM position")
    suspend fun count(): Int
}
