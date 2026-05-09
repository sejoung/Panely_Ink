package io.github.sejoung.panelyink.data.db.cover

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface CoverMetaDao {

    @Query("SELECT * FROM cover_meta WHERE book_id = :bookId LIMIT 1")
    suspend fun load(bookId: String): CoverMetaEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: CoverMetaEntity)

    @Query("DELETE FROM cover_meta WHERE book_id = :bookId")
    suspend fun delete(bookId: String)

    /**
     * LRU 정리용 — 오래된 추출 시점 순서대로. 호출자가 누적 사이즈 계산하며 끝에서
     * 자른다. status=FAILED는 디스크 파일이 없으니 정리 후보 X — OK만 가져옴.
     */
    @Query("SELECT * FROM cover_meta WHERE status = 'OK' ORDER BY extracted_at ASC")
    suspend fun loadOkOrderedByLru(): List<CoverMetaEntity>

    /** 사용자 명시 "표지 캐시 비우기" 시 호출. FAILED 메타도 함께 삭제 → 깨진 책 재시도 가능. */
    @Query("DELETE FROM cover_meta")
    suspend fun deleteAll()

    /** 폴더 진입 시 화면에 보일 책들의 메타를 batch — N개 쿼리 → 1쿼리. */
    @Query("SELECT * FROM cover_meta WHERE book_id IN (:bookIds)")
    suspend fun loadAllByIds(bookIds: List<String>): List<CoverMetaEntity>
}
