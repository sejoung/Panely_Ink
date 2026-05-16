package io.github.sejoung.panelyink.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import io.github.sejoung.panelyink.data.db.bookmark.BookmarkDao
import io.github.sejoung.panelyink.data.db.bookmark.BookmarkEntity
import io.github.sejoung.panelyink.data.db.bookindex.BookIndexDao
import io.github.sejoung.panelyink.data.db.bookindex.BookIndexEntity
import io.github.sejoung.panelyink.data.db.cover.CoverMetaDao
import io.github.sejoung.panelyink.data.db.cover.CoverMetaEntity
import io.github.sejoung.panelyink.data.db.position.PositionDao
import io.github.sejoung.panelyink.data.db.position.PositionEntity
import io.github.sejoung.panelyink.data.db.settings.BookSettingsDao
import io.github.sejoung.panelyink.data.db.settings.BookSettingsEntity

/**
 * 앱 단일 Room 데이터베이스 — M3 라이브러리 보강 / 책별 설정 / 진행률 / 표지 캐시
 * 메타가 누적될 곳.
 *
 * 버전 이력:
 * - v1: `position` 테이블 (SharedPreferences 마이그레이션)
 * - v2: `book_settings` 테이블 추가 (책별 fit/direction/trim/contrast/invert/refresh)
 * - v3: `position.page_count` 컬럼 추가 (라이브러리 진행률 배지용)
 * - v4: `cover_meta` 테이블 추가 (표지 추출 상태/시점, 깨진 책 재시도 방지)
 * - v5: `bookmark` 테이블 추가 (책별 페이지 북마크)
 * - v6: `book_settings`에서 전역 prefs로 분리된 orphan 컬럼 제거
 * - v7: `book_index` 테이블 추가 + bookmark.created_at 정렬 인덱스
 * - v8: `book_settings.spread_mode` 컬럼 추가 (두쪽 보기 토글 책별 저장 — v1.1)
 * - v9: `book_settings.orientation` 컬럼 추가 (회전 잠금 책별 저장 — v1.1)
 * - v10: `book_settings.orientation` 기본값을 `'Auto'` → `'Portrait'`로 재생성, 레거시
 *        `'Auto'` 행 값을 `'Portrait'`로 정규화 (Auto 옵션 폐기에 따른 schema 정렬 — v1.1)
 *
 * 싱글톤 보장: [PanelyDatabase.getInstance]가 더블 체크 락. 안드로이드 앱에서 DB는 프로세스당 1개.
 */
@Database(
  entities = [
    PositionEntity::class,
    BookSettingsEntity::class,
    CoverMetaEntity::class,
    BookmarkEntity::class,
    BookIndexEntity::class,
  ],
  version = 10,
  exportSchema = false,
)
abstract class PanelyDatabase : RoomDatabase() {

  abstract fun positionDao(): PositionDao
  abstract fun bookSettingsDao(): BookSettingsDao
  abstract fun coverMetaDao(): CoverMetaDao
  abstract fun bookmarkDao(): BookmarkDao
  abstract fun bookIndexDao(): BookIndexDao

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

    /**
     * v3 → v4: cover_meta 테이블 신규. 기존 표지 디스크 캐시는 그대로 — 첫 진입에
     * 메타가 없으니 재추출되지만 정상 책은 디스크 hit이라 1회 비용만.
     */
    private val MIGRATION_3_4 = object : Migration(3, 4) {
      override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
          """
                    CREATE TABLE IF NOT EXISTS `cover_meta` (
                        `book_id` TEXT NOT NULL,
                        `status` TEXT NOT NULL,
                        `source_page_index` INTEGER NOT NULL,
                        `extracted_at` INTEGER NOT NULL,
                        PRIMARY KEY(`book_id`)
                    )
                    """.trimIndent(),
        )
      }
    }

    /** v4 → v5: 페이지 북마크 테이블 신규. */
    private val MIGRATION_4_5 = object : Migration(4, 5) {
      override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
          """
                    CREATE TABLE IF NOT EXISTS `bookmark` (
                        `book_id` TEXT NOT NULL,
                        `page_index` INTEGER NOT NULL,
                        `created_at` INTEGER NOT NULL,
                        PRIMARY KEY(`book_id`, `page_index`)
                    )
                    """.trimIndent(),
        )
      }
    }

    /**
     * v5 → v6: book_settings에서 더 이상 사용하지 않는 전역 옵션 컬럼 제거.
     *
     * SQLite/Room 호환성을 위해 DROP COLUMN 대신 새 테이블 생성 → 기존 데이터 복사
     * → drop/rename 순서로 처리한다.
     */
    internal val MIGRATION_5_6 = object : Migration(5, 6) {
      override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
          """
                    CREATE TABLE IF NOT EXISTS `book_settings_new` (
                        `book_id` TEXT NOT NULL,
                        `fit_mode` TEXT NOT NULL,
                        `direction` TEXT NOT NULL,
                        `trim_enabled` INTEGER NOT NULL,
                        `contrast` REAL NOT NULL,
                        `updated_at` INTEGER NOT NULL,
                        PRIMARY KEY(`book_id`)
                    )
                    """.trimIndent(),
        )
        db.execSQL(
          """
                    INSERT INTO `book_settings_new` (
                        `book_id`,
                        `fit_mode`,
                        `direction`,
                        `trim_enabled`,
                        `contrast`,
                        `updated_at`
                    )
                    SELECT
                        `book_id`,
                        `fit_mode`,
                        `direction`,
                        `trim_enabled`,
                        `contrast`,
                        `updated_at`
                    FROM `book_settings`
                    """.trimIndent(),
        )
        db.execSQL("DROP TABLE `book_settings`")
        db.execSQL("ALTER TABLE `book_settings_new` RENAME TO `book_settings`")
      }
    }

    /**
     * v7 → v8: book_settings에 spread_mode 컬럼 추가 (두쪽 보기 토글 책별 저장).
     * 기존 행은 기본값 0(false)으로 채워져, 책을 다시 열 때 단쪽 보기로 시작.
     */
    internal val MIGRATION_7_8 = object : Migration(7, 8) {
      override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
          "ALTER TABLE `book_settings` ADD COLUMN `spread_mode` INTEGER NOT NULL DEFAULT 0",
        )
      }
    }

    /**
     * v8 → v9: book_settings에 orientation 컬럼 추가 (회전 잠금 책별 저장).
     *
     * 역사적 마이그레이션 — v1.1 베타에서 `DEFAULT 'Auto'`로 컬럼이 만들어졌다.
     * [MIGRATION_9_10]에서 default를 'Portrait'로 정규화하고 'Auto' 값 행도 모두 'Portrait'로
     * 변환하므로 여기 default가 무엇이든 결과는 동일. 다만 베타 단말의 DB와 hash 일치를
     * 위해 베타가 쓴 그대로 'Auto'로 둔다.
     */
    internal val MIGRATION_8_9 = object : Migration(8, 9) {
      override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
          "ALTER TABLE `book_settings` ADD COLUMN `orientation` TEXT NOT NULL DEFAULT 'Auto'",
        )
      }
    }

    /**
     * v9 → v10: orientation 컬럼 default를 `'Portrait'`로 정규화 + 레거시 `'Auto'` 행을 모두
     * `'Portrait'`로 치환. SQLite는 ALTER COLUMN DEFAULT를 지원하지 않으므로 v5→v6 패턴과
     * 동일하게 테이블 재생성 → 데이터 복사 → drop/rename.
     *
     * Room identity hash가 entity의 `defaultValue = "Portrait"`와 일치하도록 default 절을 일치시키는 것이 핵심.
     */
    internal val MIGRATION_9_10 = object : Migration(9, 10) {
      override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
          """
                    CREATE TABLE IF NOT EXISTS `book_settings_new` (
                        `book_id` TEXT NOT NULL,
                        `fit_mode` TEXT NOT NULL,
                        `direction` TEXT NOT NULL,
                        `trim_enabled` INTEGER NOT NULL,
                        `contrast` REAL NOT NULL,
                        `spread_mode` INTEGER NOT NULL DEFAULT 0,
                        `orientation` TEXT NOT NULL DEFAULT 'Portrait',
                        `updated_at` INTEGER NOT NULL,
                        PRIMARY KEY(`book_id`)
                    )
                    """.trimIndent(),
        )
        db.execSQL(
          """
                    INSERT INTO `book_settings_new` (
                        `book_id`,
                        `fit_mode`,
                        `direction`,
                        `trim_enabled`,
                        `contrast`,
                        `spread_mode`,
                        `orientation`,
                        `updated_at`
                    )
                    SELECT
                        `book_id`,
                        `fit_mode`,
                        `direction`,
                        `trim_enabled`,
                        `contrast`,
                        `spread_mode`,
                        CASE WHEN `orientation` = 'Auto' THEN 'Portrait' ELSE `orientation` END,
                        `updated_at`
                    FROM `book_settings`
                    """.trimIndent(),
        )
        db.execSQL("DROP TABLE `book_settings`")
        db.execSQL("ALTER TABLE `book_settings_new` RENAME TO `book_settings`")
      }
    }

    /** v6 → v7: 전체 북마크 빠른 resolve용 책 인덱스와 북마크 최신순 정렬 인덱스. */
    internal val MIGRATION_6_7 = object : Migration(6, 7) {
      override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
          """
                    CREATE TABLE IF NOT EXISTS `book_index` (
                        `book_id` TEXT NOT NULL,
                        `document_uri` TEXT NOT NULL,
                        `display_name` TEXT NOT NULL,
                        `size_bytes` INTEGER NOT NULL,
                        `mime_type` TEXT,
                        `root_uri` TEXT NOT NULL,
                        `nested_entry_name` TEXT,
                        `group_key` TEXT NOT NULL,
                        `indexed_at` INTEGER NOT NULL,
                        PRIMARY KEY(`book_id`)
                    )
                    """.trimIndent(),
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_book_index_root_uri` ON `book_index` (`root_uri`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_book_index_group_key` ON `book_index` (`group_key`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_book_index_indexed_at` ON `book_index` (`indexed_at`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_bookmark_created_at` ON `bookmark` (`created_at`)")
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
          .addMigrations(
            MIGRATION_1_2,
            MIGRATION_2_3,
            MIGRATION_3_4,
            MIGRATION_4_5,
            MIGRATION_5_6,
            MIGRATION_6_7,
            MIGRATION_7_8,
            MIGRATION_8_9,
            MIGRATION_9_10,
          )
          .build()
          .also { instance = it }
      }
  }
}
