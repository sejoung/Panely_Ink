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

    /**
     * 책의 전체 페이지 수 — M3 라이브러리 진행률 배지에 사용.
     * v0.x SharedPrefs 마이그레이션 데이터는 0(unknown)으로 시작, 사용자가 책을
     * 열면 ReaderScreen이 자동으로 갱신.
     */
    @ColumnInfo(name = "page_count", defaultValue = "0")
    val pageCount: Int,

    /** 마지막 갱신 epoch ms — 정렬(Last opened)과 LRU 정리에 사용. */
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
)
