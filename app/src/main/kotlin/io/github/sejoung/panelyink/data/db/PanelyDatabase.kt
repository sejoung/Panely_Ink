package io.github.sejoung.panelyink.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * 앱 단일 Room 데이터베이스 — M3 라이브러리 보강 / 책별 설정 / 진행률 / 표지 캐시
 * 메타가 누적될 곳.
 *
 * 버전 이력:
 * - v1: `position` 테이블 (SharedPreferences 마이그레이션)
 * - v2: `book_settings` 테이블 추가 (책별 fit/direction/trim/contrast/invert/refresh)
 * - v3: `position.page_count` 컬럼 추가 (라이브러리 진행률 배지용)
 *
 * 다음 단계 후보: `cover_meta` 테이블(표지 캐시 메타) → v4, `bookmark` 테이블 → v5
 *
 * 싱글톤 보장: [getInstance]가 더블 체크 락. 안드로이드 앱에서 DB는 프로세스당 1개.
 */
@Database(
    entities = [PositionEntity::class, BookSettingsEntity::class],
    version = 3,
    exportSchema = false,
)
abstract class PanelyDatabase : RoomDatabase() {

    abstract fun positionDao(): PositionDao
    abstract fun bookSettingsDao(): BookSettingsDao

    companion object {
        private const val DB_NAME = "panely_ink.db"

        /** v1 → v2: book_settings 테이블 신규. 기존 position 데이터는 그대로 유지. */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `book_settings` (
                        `book_id` TEXT NOT NULL,
                        `fit_mode` TEXT NOT NULL,
                        `direction` TEXT NOT NULL,
                        `trim_enabled` INTEGER NOT NULL,
                        `contrast` REAL NOT NULL,
                        `invert_enabled` INTEGER NOT NULL,
                        `full_refresh_interval` INTEGER NOT NULL,
                        `updated_at` INTEGER NOT NULL,
                        PRIMARY KEY(`book_id`)
                    )
                    """.trimIndent(),
                )
            }
        }

        /**
         * v2 → v3: position에 page_count 컬럼 추가. 기존 행은 0(unknown)으로
         * 시작하고, 사용자가 책을 열면 ReaderScreen이 자동으로 채운다.
         */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `position` ADD COLUMN `page_count` INTEGER NOT NULL DEFAULT 0",
                )
            }
        }

        @Volatile
        private var instance: PanelyDatabase? = null

        fun getInstance(context: Context): PanelyDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    PanelyDatabase::class.java,
                    DB_NAME,
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                    .build()
                    .also { instance = it }
            }
    }
}
