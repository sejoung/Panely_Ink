package io.github.sejoung.panelyink.data.db.settings

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 책별 사용자 설정 — PRD §6.1 "Contrast/Gamma 책별 저장" / "같은 시리즈 다음 권에
 * propagate"의 영속 레이어.
 *
 * v1.0의 모든 사용자 가변 설정을 한 행에 둠 — 책당 1행이라 row 수가 곧 책 수, 메모리/
 * 디스크 부담 작음. 각 컬럼은 sealed/enum이 아닌 단순 타입(Room이 자동 매핑).
 *
 * - [fitMode]: `serializeFitMode` 결과 String — `"FitScreen"`, `"Zoom:1.5"` 등
 * - [direction]: `ReadingDirection.name` — `"Ltr"`, `"Rtl"`, `"VerticalScroll"`
 * - [contrast]: 0.5..2.0 (안전가드는 `ContrastMatrix.MIN/MAX`)
 */
@Entity(tableName = "book_settings")
data class BookSettingsEntity(
    @PrimaryKey
    @ColumnInfo(name = "book_id")
    val bookId: String,

    @ColumnInfo(name = "fit_mode")
    val fitMode: String,

    @ColumnInfo(name = "direction")
    val direction: String,

    @ColumnInfo(name = "trim_enabled")
    val trimEnabled: Boolean,

    @ColumnInfo(name = "contrast")
    val contrast: Float,

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
)
