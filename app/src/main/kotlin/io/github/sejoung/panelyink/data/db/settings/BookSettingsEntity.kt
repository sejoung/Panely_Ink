package io.github.sejoung.panelyink.data.db.settings

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 책별 사용자 **명시적 override** — v1.1.
 *
 * 모든 사용자-가변 필드가 nullable. NULL은 "사용자가 그 필드는 안 건드림 → 진입 시 전역값을 따름"을 의미한다.
 * non-NULL은 "사용자가 그 값으로 명시 선택했음 → 전역값 변경에도 유지됨"을 의미.
 *
 * - [fitMode]: `serializeFitMode` 결과 String — `"FitScreen"`, `"Zoom:1.5"` 등 (null 가능)
 * - [direction]: `ReadingDirection.name` (null 가능)
 * - [contrast]: 0.5..2.0 (null 가능)
 * - [orientation]: `ReaderOrientation.name` (null 가능)
 *
 * 빈 override (모든 필드 NULL)는 repository가 row 자체를 삭제 — DB가 깨끗하게 유지되어 다음 진입에 전역값 그대로 사용.
 */
@Entity(tableName = "book_settings")
data class BookSettingsEntity(
    @PrimaryKey
    @ColumnInfo(name = "book_id")
    val bookId: String,

    @ColumnInfo(name = "fit_mode")
    val fitMode: String?,

    @ColumnInfo(name = "direction")
    val direction: String?,

    @ColumnInfo(name = "trim_enabled")
    val trimEnabled: Boolean?,

    @ColumnInfo(name = "contrast")
    val contrast: Float?,

    @ColumnInfo(name = "spread_mode")
    val spreadMode: Boolean?,

    @ColumnInfo(name = "cover_alone")
    val coverAlone: Boolean?,

    @ColumnInfo(name = "orientation")
    val orientation: String?,

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
)
