package io.github.sejoung.panelyink.data.db.bookindex

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 전체 북마크/정리에서 SAF 전체 재스캔을 줄이기 위한 책 인덱스.
 *
 * 실제 파일 권한/존재 여부의 최종 truth는 SAF지만, 최근 스캔에서 발견한 책 메타를
 * Room에 저장해 전체 북마크 화면이 먼저 빠른 DB lookup을 시도할 수 있게 한다.
 */
@Entity(
  tableName = "book_index",
  indices = [
    Index(value = ["root_uri"]),
    Index(value = ["group_key"]),
    Index(value = ["indexed_at"]),
  ],
)
data class BookIndexEntity(
  @PrimaryKey
  @ColumnInfo(name = "book_id")
  val bookId: String,

  @ColumnInfo(name = "document_uri")
  val documentUri: String,

  @ColumnInfo(name = "display_name")
  val displayName: String,

  @ColumnInfo(name = "size_bytes")
  val sizeBytes: Long,

  @ColumnInfo(name = "mime_type")
  val mimeType: String?,

  @ColumnInfo(name = "root_uri")
  val rootUri: String,

  @ColumnInfo(name = "nested_entry_name")
  val nestedEntryName: String?,

  /**
   * 같은 화면/가상 시리즈 안에서 함께 열릴 siblings 묶음.
   * 일반 폴더 책들은 부모 폴더 uri, ZIP-of-CBZ 자식들은 부모 zip uri를 사용한다.
   */
  @ColumnInfo(name = "group_key")
  val groupKey: String,

  @ColumnInfo(name = "indexed_at")
  val indexedAt: Long,
)
