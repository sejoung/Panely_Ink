package io.github.sejoung.panelyink.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 책별 마지막 페이지(Resume) — PRD §6.1.
 *
 * v0.x SharedPreferences("panely_ink_positions") 데이터를 [DatabaseMigration]가
 * 첫 실행 시 1회 복사. bookId 키 형식은 SHA-1 hex(`PositionKey.bookIdFromUri`).
 */
@Entity(tableName = "position")
data class PositionEntity(
    @PrimaryKey
    @ColumnInfo(name = "book_id")
    val bookId: String,

    @ColumnInfo(name = "page_index")
    val pageIndex: Int,

    /** 마지막 갱신 epoch ms — 정렬(Last opened)과 LRU 정리에 사용. */
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
)
