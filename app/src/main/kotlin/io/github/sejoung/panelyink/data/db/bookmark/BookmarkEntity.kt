package io.github.sejoung.panelyink.data.db.bookmark

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index

/**
 * 페이지 북마크. 한 책 안에서 같은 페이지는 1개만 유지한다.
 */
@Entity(
    tableName = "bookmark",
    primaryKeys = ["book_id", "page_index"],
    indices = [Index(value = ["created_at"])],
)
data class BookmarkEntity(
    @ColumnInfo(name = "book_id")
    val bookId: String,

    @ColumnInfo(name = "page_index")
    val pageIndex: Int,

    @ColumnInfo(name = "created_at")
    val createdAt: Long,
)
