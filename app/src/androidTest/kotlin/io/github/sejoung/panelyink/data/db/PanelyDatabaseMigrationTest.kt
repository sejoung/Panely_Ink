package io.github.sejoung.panelyink.data.db

import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Room 마이그레이션 검증.
 *
 * `exportSchema = false`라 `MigrationTestHelper`를 쓸 수 없어 옛 버전 DDL은 손으로 적는다. 두 종류로 나눈다:
 *
 * - **단계(step) 테스트**: Room을 거치지 않고 [SupportSQLiteOpenHelper]로 vX DB를 만든 뒤
 *   `MIGRATION_X_Y.migrate(db)`만 직접 실행해 **vY 시점의 schema/데이터**를 검증한다.
 *   Room(최신 버전)으로 열면 중간 버전 schema를 볼 수 없고, 등록 안 된 구간 때문에
 *   `IllegalStateException("A migration from X to N was required but not found")`이 나기 때문.
 * - **전체 체인(chain) 테스트**: 옛 버전 DB를 [PanelyDatabase.ALL_MIGRATIONS] 전부와 함께 Room으로 열어
 *   최신 버전까지 올린다. `openHelper.writableDatabase`가 Room의 schema 검증(entity ↔ 실제 테이블)을
 *   강제로 돌리므로, 앞으로 마이그레이션이 추가돼도 이 테스트들은 그대로 유효하다.
 */
@RunWith(AndroidJUnit4::class)
class PanelyDatabaseMigrationTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(DB_NAME)
    }

    @After
    fun tearDown() {
        context.deleteDatabase(DB_NAME)
    }

    // ---------------------------------------------------------------------------------------
    // 마이그레이션 목록 자체의 정합성
    // ---------------------------------------------------------------------------------------

    @Test
    fun allMigrationsFormContiguousChainFromVersion1() {
        // v1부터 한 단계씩 빠짐없이 이어져야 어떤 옛 버전에서 올라와도 경로가 존재한다.
        val migrations = PanelyDatabase.ALL_MIGRATIONS
        assertTrue("ALL_MIGRATIONS must not be empty", migrations.isNotEmpty())
        migrations.forEachIndexed { index, migration ->
            assertEquals("startVersion of migration #$index", index + 1, migration.startVersion)
            assertEquals("endVersion of migration #$index", index + 2, migration.endVersion)
        }
    }

    // ---------------------------------------------------------------------------------------
    // 단계(step) 테스트 — MIGRATION_X_Y.migrate(db)만 직접 실행
    // ---------------------------------------------------------------------------------------

    @Test
    fun migration1To2AddsBookSettingsTableAndKeepsPosition() {
        withRawDatabase(version = 1) { db ->
            db.execSQL("INSERT INTO `position` (`book_id`, `page_index`, `updated_at`) VALUES ('book', 7, 1000)")

            runMigration(db, PanelyDatabase.MIGRATION_1_2)

            assertEquals(
                setOf(
                    "book_id",
                    "fit_mode",
                    "direction",
                    "trim_enabled",
                    "contrast",
                    "invert_enabled",
                    "full_refresh_interval",
                    "updated_at",
                ),
                columnsOf(db, "book_settings").keys,
            )
            db.query("SELECT `page_index`, `updated_at` FROM `position` WHERE `book_id` = 'book'").use { cursor ->
                assertEquals(true, cursor.moveToFirst())
                assertEquals(7, cursor.getInt(0))
                assertEquals(1000L, cursor.getLong(1))
            }
        }
    }

    @Test
    fun migration2To3AddsPageCountWithDefaultZero() {
        withRawDatabase(version = 2) { db ->
            db.execSQL("INSERT INTO `position` (`book_id`, `page_index`, `updated_at`) VALUES ('book', 7, 1000)")

            runMigration(db, PanelyDatabase.MIGRATION_2_3)

            val pageCount = columnsOf(db, "position").getValue("page_count")
            assertEquals(true, pageCount.notNull)
            assertEquals("0", pageCount.defaultValue)
            // 기존 행은 0(unknown)으로 시작.
            db.query("SELECT `page_index`, `page_count`, `updated_at` FROM `position` WHERE `book_id` = 'book'")
                .use { cursor ->
                    assertEquals(true, cursor.moveToFirst())
                    assertEquals(7, cursor.getInt(0))
                    assertEquals(0, cursor.getInt(1))
                    assertEquals(1000L, cursor.getLong(2))
                }
        }
    }

    @Test
    fun migration3To4AddsCoverMetaTable() {
        withRawDatabase(version = 3) { db ->
            runMigration(db, PanelyDatabase.MIGRATION_3_4)

            assertEquals(
                setOf("book_id", "status", "source_page_index", "extracted_at"),
                columnsOf(db, "cover_meta").keys,
            )
        }
    }

    @Test
    fun migration4To5AddsBookmarkTableWithCompositePrimaryKey() {
        withRawDatabase(version = 4) { db ->
            runMigration(db, PanelyDatabase.MIGRATION_4_5)

            val columns = columnsOf(db, "bookmark")
            assertEquals(setOf("book_id", "page_index", "created_at"), columns.keys)
            // 복합 PK(book_id, page_index) — 한 책 안에서 같은 페이지는 1개만.
            assertEquals(1, columns.getValue("book_id").primaryKeyPosition)
            assertEquals(2, columns.getValue("page_index").primaryKeyPosition)
            assertEquals(0, columns.getValue("created_at").primaryKeyPosition)
        }
    }

    @Test
    fun migration5To6DropsBookSettingsOrphanColumnsAndKeepsData() {
        withRawDatabase(version = 5) { db ->
            db.execSQL(
                """
                INSERT INTO `book_settings` (
                    `book_id`,
                    `fit_mode`,
                    `direction`,
                    `trim_enabled`,
                    `contrast`,
                    `invert_enabled`,
                    `full_refresh_interval`,
                    `updated_at`
                ) VALUES ('book', 'FitWidth', 'Rtl', 0, 1.25, 1, 3, 1234)
                """.trimIndent(),
            )

            runMigration(db, PanelyDatabase.MIGRATION_5_6)

            val names = columnsOf(db, "book_settings").keys
            assertEquals(
                setOf("book_id", "fit_mode", "direction", "trim_enabled", "contrast", "updated_at"),
                names,
            )
            assertFalse("invert_enabled should be removed", "invert_enabled" in names)
            assertFalse("full_refresh_interval should be removed", "full_refresh_interval" in names)
            assertFalse("temp table should be renamed away", "book_settings_new" in tableNames(db))

            db.query("SELECT * FROM `book_settings` WHERE `book_id` = 'book'").use { cursor ->
                assertEquals(true, cursor.moveToFirst())
                assertEquals("FitWidth", cursor.getString(cursor.getColumnIndexOrThrow("fit_mode")))
                assertEquals("Rtl", cursor.getString(cursor.getColumnIndexOrThrow("direction")))
                assertEquals(0, cursor.getInt(cursor.getColumnIndexOrThrow("trim_enabled")))
                assertEquals(1.25f, cursor.getFloat(cursor.getColumnIndexOrThrow("contrast")), 0f)
                assertEquals(1234L, cursor.getLong(cursor.getColumnIndexOrThrow("updated_at")))
            }
        }
    }

    @Test
    fun migration6To7AddsBookIndexAndBookmarkCreatedAtIndex() {
        withRawDatabase(version = 6) { db ->
            db.execSQL("INSERT INTO `bookmark` (`book_id`, `page_index`, `created_at`) VALUES ('book', 3, 5000)")

            runMigration(db, PanelyDatabase.MIGRATION_6_7)

            assertEquals(
                setOf(
                    "book_id",
                    "document_uri",
                    "display_name",
                    "size_bytes",
                    "mime_type",
                    "root_uri",
                    "nested_entry_name",
                    "group_key",
                    "indexed_at",
                ),
                columnsOf(db, "book_index").keys,
            )
            assertEquals(true, "index_bookmark_created_at" in indexNamesOf(db, "bookmark"))
            assertTrue(
                indexNamesOf(db, "book_index").containsAll(
                    setOf(
                        "index_book_index_root_uri",
                        "index_book_index_group_key",
                        "index_book_index_indexed_at",
                    ),
                ),
            )
            // 기존 북마크는 그대로.
            db.query("SELECT `created_at` FROM `bookmark` WHERE `book_id` = 'book' AND `page_index` = 3")
                .use { cursor ->
                    assertEquals(true, cursor.moveToFirst())
                    assertEquals(5000L, cursor.getLong(0))
                }
        }
    }

    @Test
    fun migration7To8AddsSpreadModeWithDefaultFalse() {
        withRawDatabase(version = 7) { db ->
            db.execSQL(
                """
                INSERT INTO `book_settings` (`book_id`, `fit_mode`, `direction`, `trim_enabled`, `contrast`, `updated_at`)
                VALUES ('book', 'FitWidth', 'Rtl', 1, 1.25, 1234)
                """.trimIndent(),
            )

            runMigration(db, PanelyDatabase.MIGRATION_7_8)

            val columns = columnsOf(db, "book_settings")
            assertEquals(
                setOf("book_id", "fit_mode", "direction", "trim_enabled", "contrast", "spread_mode", "updated_at"),
                columns.keys,
            )
            val spreadMode = columns.getValue("spread_mode")
            assertEquals(true, spreadMode.notNull)
            assertEquals("0", spreadMode.defaultValue)

            // 기존 행은 0(false) — 책을 다시 열 때 단쪽 보기로 시작.
            db.query("SELECT * FROM `book_settings` WHERE `book_id` = 'book'").use { cursor ->
                assertEquals(true, cursor.moveToFirst())
                assertEquals(0, cursor.getInt(cursor.getColumnIndexOrThrow("spread_mode")))
                assertEquals("FitWidth", cursor.getString(cursor.getColumnIndexOrThrow("fit_mode")))
                assertEquals("Rtl", cursor.getString(cursor.getColumnIndexOrThrow("direction")))
                assertEquals(1, cursor.getInt(cursor.getColumnIndexOrThrow("trim_enabled")))
                assertEquals(1.25f, cursor.getFloat(cursor.getColumnIndexOrThrow("contrast")), 0f)
                assertEquals(1234L, cursor.getLong(cursor.getColumnIndexOrThrow("updated_at")))
            }
        }
    }

    @Test
    fun migration8To9AddsOrientationWithLegacyAutoDefault() {
        withRawDatabase(version = 8) { db ->
            db.execSQL(
                """
                INSERT INTO `book_settings` (
                    `book_id`, `fit_mode`, `direction`, `trim_enabled`, `contrast`, `spread_mode`, `updated_at`
                ) VALUES ('book', 'FitWidth', 'Rtl', 0, 1.25, 1, 1234)
                """.trimIndent(),
            )

            runMigration(db, PanelyDatabase.MIGRATION_8_9)

            val orientation = columnsOf(db, "book_settings").getValue("orientation")
            assertEquals(true, orientation.notNull)
            // 베타 단말 DB와 맞추기 위해 역사적 default 'Auto'를 그대로 둔다 (v10에서 'Portrait'로 정규화).
            assertEquals("'Auto'", orientation.defaultValue)

            db.query("SELECT * FROM `book_settings` WHERE `book_id` = 'book'").use { cursor ->
                assertEquals(true, cursor.moveToFirst())
                assertEquals("Auto", cursor.getString(cursor.getColumnIndexOrThrow("orientation")))
                assertEquals(1, cursor.getInt(cursor.getColumnIndexOrThrow("spread_mode")))
                assertEquals("FitWidth", cursor.getString(cursor.getColumnIndexOrThrow("fit_mode")))
                assertEquals(1234L, cursor.getLong(cursor.getColumnIndexOrThrow("updated_at")))
            }
        }
    }

    @Test
    fun migration9To10NormalizesAutoOrientationToPortrait() {
        // v1.1 베타 단계에서 orientation 컬럼이 'Auto' default로 만들어졌고, 행 데이터에도
        // 'Auto'가 박혀 있을 수 있다. v10 마이그레이션이 'Portrait'로 정규화하는지 검증.
        withRawDatabase(version = 9) { db ->
            insertFullBookSettingsRow(db, bookId = "legacy", orientation = "Auto", updatedAt = 1111)
            insertFullBookSettingsRow(db, bookId = "landscape", orientation = "Landscape", updatedAt = 1112)

            runMigration(db, PanelyDatabase.MIGRATION_9_10)

            val orientation = columnsOf(db, "book_settings").getValue("orientation")
            assertEquals(true, orientation.notNull)
            assertEquals("'Portrait'", orientation.defaultValue)
            assertFalse("temp table should be renamed away", "book_settings_new" in tableNames(db))

            db.query("SELECT `orientation` FROM `book_settings` WHERE `book_id` = 'legacy'").use { cursor ->
                assertEquals(true, cursor.moveToFirst())
                assertEquals("Portrait", cursor.getString(0))
            }
            // 'Auto'가 아닌 값은 건드리지 않는다.
            db.query("SELECT `orientation` FROM `book_settings` WHERE `book_id` = 'landscape'").use { cursor ->
                assertEquals(true, cursor.moveToFirst())
                assertEquals("Landscape", cursor.getString(0))
            }
        }
    }

    @Test
    fun migration10To11MakesSettingsColumnsNullableAndPreservesData() {
        // v10에서 NOT NULL이었던 6필드가 v11에서 nullable로. 기존 행은 그대로 보존되어야 한다.
        withRawDatabase(version = 10) { db ->
            insertFullBookSettingsRow(db, bookId = "legacy", orientation = "Landscape", updatedAt = 2222)

            runMigration(db, PanelyDatabase.MIGRATION_10_11)

            // schema: 6필드 모두 nullable이어야 한다. book_id와 updated_at만 NOT NULL 유지.
            val columns = columnsOf(db, "book_settings")
            assertEquals(
                setOf(
                    "book_id",
                    "fit_mode",
                    "direction",
                    "trim_enabled",
                    "contrast",
                    "spread_mode",
                    "orientation",
                    "updated_at",
                ),
                columns.keys,
            )
            assertEquals(true, columns.getValue("book_id").notNull)
            assertEquals(true, columns.getValue("updated_at").notNull)
            assertEquals(false, columns.getValue("fit_mode").notNull)
            assertEquals(false, columns.getValue("direction").notNull)
            assertEquals(false, columns.getValue("trim_enabled").notNull)
            assertEquals(false, columns.getValue("contrast").notNull)
            assertEquals(false, columns.getValue("spread_mode").notNull)
            assertEquals(false, columns.getValue("orientation").notNull)

            // 기존 row 보존 — 기존 책은 '전 필드 명시 override' 상태로 유지되어 동작이 깨지지 않음.
            db.query("SELECT * FROM `book_settings` WHERE `book_id` = 'legacy'").use { cursor ->
                assertEquals(true, cursor.moveToFirst())
                assertEquals("FitWidth", cursor.getString(cursor.getColumnIndexOrThrow("fit_mode")))
                assertEquals("Rtl", cursor.getString(cursor.getColumnIndexOrThrow("direction")))
                assertEquals(0, cursor.getInt(cursor.getColumnIndexOrThrow("trim_enabled")))
                assertEquals(1.25f, cursor.getFloat(cursor.getColumnIndexOrThrow("contrast")), 0f)
                assertEquals(1, cursor.getInt(cursor.getColumnIndexOrThrow("spread_mode")))
                assertEquals("Landscape", cursor.getString(cursor.getColumnIndexOrThrow("orientation")))
                assertEquals(2222L, cursor.getLong(cursor.getColumnIndexOrThrow("updated_at")))
            }

            // 새 책의 sparse insert가 가능한지 (모든 가변 컬럼 NULL) — 이게 깨지면 sparse override 모델 자체가 작동 안 함.
            db.execSQL("INSERT INTO `book_settings` (`book_id`, `updated_at`) VALUES ('sparse', 3333)")
            db.query("SELECT `fit_mode`, `orientation` FROM `book_settings` WHERE `book_id` = 'sparse'")
                .use { cursor ->
                    assertEquals(true, cursor.moveToFirst())
                    assertEquals(true, cursor.isNull(0))
                    assertEquals(true, cursor.isNull(1))
                }
        }
    }

    @Test
    fun migration11To12AddsNullableCoverAloneColumnAndPreservesData() {
        withRawDatabase(version = 11) { db ->
            insertFullBookSettingsRow(db, bookId = "legacy", orientation = "Landscape", updatedAt = 2222)

            runMigration(db, PanelyDatabase.MIGRATION_11_12)

            // cover_alone 컬럼이 nullable로 추가되었는지.
            val coverAlone = columnsOf(db, "book_settings").getValue("cover_alone")
            assertEquals(false, coverAlone.notNull)
            assertEquals(null, coverAlone.defaultValue)

            // 기존 행은 보존되고 cover_alone은 NULL(미설정 → 진입 시 false 합성).
            db.query("SELECT * FROM `book_settings` WHERE `book_id` = 'legacy'").use { cursor ->
                assertEquals(true, cursor.moveToFirst())
                assertEquals("FitWidth", cursor.getString(cursor.getColumnIndexOrThrow("fit_mode")))
                assertEquals(1, cursor.getInt(cursor.getColumnIndexOrThrow("spread_mode")))
                assertEquals(true, cursor.isNull(cursor.getColumnIndexOrThrow("cover_alone")))
                assertEquals(2222L, cursor.getLong(cursor.getColumnIndexOrThrow("updated_at")))
            }
        }
    }

    // ---------------------------------------------------------------------------------------
    // 전체 체인(chain) 테스트 — Room + ALL_MIGRATIONS로 최신 버전까지
    // ---------------------------------------------------------------------------------------

    @Test
    fun fullChainFromVersion1OpensAtLatestAndKeepsPosition() {
        // 가장 오래된 schema(position 테이블만, page_count 없음)에서 최신까지.
        withRawDatabase(version = 1) { db ->
            db.execSQL("INSERT INTO `position` (`book_id`, `page_index`, `updated_at`) VALUES ('book', 7, 1000)")
        }

        withMigratedRoomDatabase { db ->
            db.query("SELECT `page_index`, `page_count`, `updated_at` FROM `position` WHERE `book_id` = 'book'")
                .use { cursor ->
                    assertEquals(true, cursor.moveToFirst())
                    assertEquals(7, cursor.getInt(0))
                    assertEquals(0, cursor.getInt(1))
                    assertEquals(1000L, cursor.getLong(2))
                }
            assertRowCount(db, "book_settings", 0)
            assertRowCount(db, "cover_meta", 0)
            assertRowCount(db, "bookmark", 0)
            assertRowCount(db, "book_index", 0)
        }
    }

    @Test
    fun fullChainFromVersion7ReleaseOpensAtLatestAndKeepsData() {
        // v7 = 1.0 릴리스 schema. 실사용자 DB가 최신 버전으로 올라오는 경로.
        withRawDatabase(version = 7) { db ->
            db.execSQL(
                "INSERT INTO `position` (`book_id`, `page_index`, `page_count`, `updated_at`) " +
                    "VALUES ('book', 12, 200, 1000)",
            )
            // page_count를 생략한 행 — 컬럼 default 0이 살아있어야 한다.
            db.execSQL("INSERT INTO `position` (`book_id`, `page_index`, `updated_at`) VALUES ('unknown', 3, 1001)")
            db.execSQL(
                """
                INSERT INTO `book_settings` (`book_id`, `fit_mode`, `direction`, `trim_enabled`, `contrast`, `updated_at`)
                VALUES ('book', 'FitWidth', 'Rtl', 1, 1.25, 1234)
                """.trimIndent(),
            )
            db.execSQL(
                "INSERT INTO `cover_meta` (`book_id`, `status`, `source_page_index`, `extracted_at`) " +
                    "VALUES ('book', 'OK', 0, 4000)",
            )
            db.execSQL("INSERT INTO `bookmark` (`book_id`, `page_index`, `created_at`) VALUES ('book', 3, 5000)")
            db.execSQL("INSERT INTO `bookmark` (`book_id`, `page_index`, `created_at`) VALUES ('book', 9, 5001)")
            db.execSQL(
                """
                INSERT INTO `book_index` (
                    `book_id`, `document_uri`, `display_name`, `size_bytes`, `mime_type`,
                    `root_uri`, `nested_entry_name`, `group_key`, `indexed_at`
                ) VALUES ('book', 'content://doc/book', 'book.cbz', 123456, NULL, 'content://root', NULL, 'content://root', 6000)
                """.trimIndent(),
            )
        }

        withMigratedRoomDatabase { db ->
            db.query("SELECT `page_index`, `page_count`, `updated_at` FROM `position` WHERE `book_id` = 'book'")
                .use { cursor ->
                    assertEquals(true, cursor.moveToFirst())
                    assertEquals(12, cursor.getInt(0))
                    assertEquals(200, cursor.getInt(1))
                    assertEquals(1000L, cursor.getLong(2))
                }
            db.query("SELECT `page_count` FROM `position` WHERE `book_id` = 'unknown'").use { cursor ->
                assertEquals(true, cursor.moveToFirst())
                assertEquals(0, cursor.getInt(0))
            }

            // v1.0 책 설정은 '전 필드 명시 override'로 보존. v1.1에서 추가된 필드는 각 마이그레이션 default를 따른다:
            // spread_mode=0(7→8), orientation='Auto'(8→9) → 'Portrait'(9→10), cover_alone=NULL(11→12).
            db.query("SELECT * FROM `book_settings` WHERE `book_id` = 'book'").use { cursor ->
                assertEquals(true, cursor.moveToFirst())
                assertEquals("FitWidth", cursor.getString(cursor.getColumnIndexOrThrow("fit_mode")))
                assertEquals("Rtl", cursor.getString(cursor.getColumnIndexOrThrow("direction")))
                assertEquals(1, cursor.getInt(cursor.getColumnIndexOrThrow("trim_enabled")))
                assertEquals(1.25f, cursor.getFloat(cursor.getColumnIndexOrThrow("contrast")), 0f)
                assertEquals(0, cursor.getInt(cursor.getColumnIndexOrThrow("spread_mode")))
                assertEquals("Portrait", cursor.getString(cursor.getColumnIndexOrThrow("orientation")))
                assertEquals(true, cursor.isNull(cursor.getColumnIndexOrThrow("cover_alone")))
                assertEquals(1234L, cursor.getLong(cursor.getColumnIndexOrThrow("updated_at")))
            }

            db.query("SELECT `status`, `source_page_index`, `extracted_at` FROM `cover_meta` WHERE `book_id` = 'book'")
                .use { cursor ->
                    assertEquals(true, cursor.moveToFirst())
                    assertEquals("OK", cursor.getString(0))
                    assertEquals(0, cursor.getInt(1))
                    assertEquals(4000L, cursor.getLong(2))
                }

            db.query("SELECT `page_index`, `created_at` FROM `bookmark` WHERE `book_id` = 'book' ORDER BY `page_index`")
                .use { cursor ->
                    assertEquals(2, cursor.count)
                    assertEquals(true, cursor.moveToFirst())
                    assertEquals(3, cursor.getInt(0))
                    assertEquals(5000L, cursor.getLong(1))
                    assertEquals(true, cursor.moveToNext())
                    assertEquals(9, cursor.getInt(0))
                    assertEquals(5001L, cursor.getLong(1))
                }

            db.query("SELECT * FROM `book_index` WHERE `book_id` = 'book'").use { cursor ->
                assertEquals(true, cursor.moveToFirst())
                assertEquals("content://doc/book", cursor.getString(cursor.getColumnIndexOrThrow("document_uri")))
                assertEquals("book.cbz", cursor.getString(cursor.getColumnIndexOrThrow("display_name")))
                assertEquals(123456L, cursor.getLong(cursor.getColumnIndexOrThrow("size_bytes")))
                assertEquals(true, cursor.isNull(cursor.getColumnIndexOrThrow("mime_type")))
                assertEquals("content://root", cursor.getString(cursor.getColumnIndexOrThrow("root_uri")))
                assertEquals(true, cursor.isNull(cursor.getColumnIndexOrThrow("nested_entry_name")))
                assertEquals("content://root", cursor.getString(cursor.getColumnIndexOrThrow("group_key")))
                assertEquals(6000L, cursor.getLong(cursor.getColumnIndexOrThrow("indexed_at")))
            }
        }
    }

    @Test
    fun fullChainFromVersion9BetaNormalizesAutoOrientationAndOpensAtLatest() {
        // v1.1 베타 단말: orientation default/값이 'Auto'인 DB가 최신 schema 검증을 통과해야 한다.
        withRawDatabase(version = 9) { db ->
            insertFullBookSettingsRow(db, bookId = "legacy", orientation = "Auto", updatedAt = 1111)
        }

        withMigratedRoomDatabase { db ->
            db.query("SELECT * FROM `book_settings` WHERE `book_id` = 'legacy'").use { cursor ->
                assertEquals(true, cursor.moveToFirst())
                assertEquals("Portrait", cursor.getString(cursor.getColumnIndexOrThrow("orientation")))
                assertEquals("FitWidth", cursor.getString(cursor.getColumnIndexOrThrow("fit_mode")))
                assertEquals(1, cursor.getInt(cursor.getColumnIndexOrThrow("spread_mode")))
                assertEquals(true, cursor.isNull(cursor.getColumnIndexOrThrow("cover_alone")))
                assertEquals(1111L, cursor.getLong(cursor.getColumnIndexOrThrow("updated_at")))
            }
        }
    }

    // ---------------------------------------------------------------------------------------
    // helpers
    // ---------------------------------------------------------------------------------------

    /**
     * Room을 거치지 않고 [version] 시점 schema의 DB 파일을 열어 [block]을 실행한다.
     * 파일이 없으면 [createSchema]로 손으로 적은 DDL을 만들고 `user_version`을 [version]으로 찍는다.
     * 최신 버전으로 여는 게 아니므로 Room의 "migration required but not found" 검사에 걸리지 않는다.
     */
    private fun withRawDatabase(version: Int, block: (SupportSQLiteDatabase) -> Unit) {
        val configuration = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(DB_NAME)
            .callback(
                object : SupportSQLiteOpenHelper.Callback(version) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        createSchema(db, version)
                    }

                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
                        throw AssertionError("raw test DB must not be upgraded: $oldVersion -> $newVersion")
                    }
                },
            )
            .build()
        val helper = FrameworkSQLiteOpenHelperFactory().create(configuration)
        try {
            block(helper.writableDatabase)
        } finally {
            helper.close()
        }
    }

    /** Room이 `onUpgrade`에서 하는 것처럼 트랜잭션 안에서 마이그레이션 1개만 실행. */
    private fun runMigration(db: SupportSQLiteDatabase, migration: Migration) {
        db.beginTransaction()
        try {
            migration.migrate(db)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /**
     * 이미 만들어 둔 옛 버전 DB 파일을 production과 같은 [PanelyDatabase.ALL_MIGRATIONS]로 Room에서 연다.
     *
     * `openHelper.writableDatabase`가 마이그레이션 + Room schema 검증을 강제로 실행 — 경로가 끊겼거나
     * 최종 schema가 entity와 다르면 여기서 `IllegalStateException`으로 실패한다. 통과하면 공통 최종 schema
     * (버전, nullable 컬럼, 인덱스, sparse insert)를 확인한 뒤 [block]으로 데이터 검증을 넘긴다.
     */
    private fun withMigratedRoomDatabase(block: (SupportSQLiteDatabase) -> Unit) {
        val roomDb = Room.databaseBuilder(context, PanelyDatabase::class.java, DB_NAME)
            .addMigrations(*PanelyDatabase.ALL_MIGRATIONS)
            .allowMainThreadQueries()
            .build()
        try {
            val db = roomDb.openHelper.writableDatabase
            assertLatestSchema(db)
            block(db)
        } finally {
            roomDb.close()
        }
    }

    private fun assertLatestSchema(db: SupportSQLiteDatabase) {
        assertEquals(PanelyDatabase.ALL_MIGRATIONS.last().endVersion, db.version)

        val position = columnsOf(db, "position")
        assertEquals(setOf("book_id", "page_index", "page_count", "updated_at"), position.keys)
        assertEquals("0", position.getValue("page_count").defaultValue)

        // book_settings: book_id/updated_at만 NOT NULL, 나머지(cover_alone 포함)는 전부 nullable.
        val bookSettings = columnsOf(db, "book_settings")
        assertTrue(
            "book_settings columns: ${bookSettings.keys}",
            bookSettings.keys.containsAll(
                setOf(
                    "book_id",
                    "fit_mode",
                    "direction",
                    "trim_enabled",
                    "contrast",
                    "spread_mode",
                    "cover_alone",
                    "orientation",
                    "updated_at",
                ),
            ),
        )
        assertFalse("invert_enabled should be removed", "invert_enabled" in bookSettings.keys)
        assertFalse("full_refresh_interval should be removed", "full_refresh_interval" in bookSettings.keys)
        bookSettings.forEach { (name, column) ->
            val expectedNotNull = name == "book_id" || name == "updated_at"
            assertEquals("notnull of book_settings.$name", expectedNotNull, column.notNull)
        }

        assertTrue(tableNames(db).containsAll(setOf("cover_meta", "bookmark", "book_index")))
        assertEquals(true, "index_bookmark_created_at" in indexNamesOf(db, "bookmark"))
        assertTrue(
            indexNamesOf(db, "book_index").containsAll(
                setOf(
                    "index_book_index_root_uri",
                    "index_book_index_group_key",
                    "index_book_index_indexed_at",
                ),
            ),
        )

        // sparse override 모델: 가변 컬럼 전부 NULL인 행이 들어가야 한다.
        db.execSQL("INSERT INTO `book_settings` (`book_id`, `updated_at`) VALUES ('__sparse__', 3333)")
        db.query("SELECT `fit_mode`, `cover_alone` FROM `book_settings` WHERE `book_id` = '__sparse__'")
            .use { cursor ->
                assertEquals(true, cursor.moveToFirst())
                assertEquals(true, cursor.isNull(0))
                assertEquals(true, cursor.isNull(1))
            }
        db.execSQL("DELETE FROM `book_settings` WHERE `book_id` = '__sparse__'")
    }

    private fun assertRowCount(db: SupportSQLiteDatabase, table: String, expected: Int) {
        db.query("SELECT COUNT(*) FROM `$table`").use { cursor ->
            assertEquals(true, cursor.moveToFirst())
            assertEquals("row count of $table", expected, cursor.getInt(0))
        }
    }

    /** v8~v11 공통 8컬럼 행. orientation 컬럼이 없는 v8 이하에서는 쓰지 않는다. */
    private fun insertFullBookSettingsRow(
        db: SupportSQLiteDatabase,
        bookId: String,
        orientation: String,
        updatedAt: Long,
    ) {
        db.execSQL(
            """
            INSERT INTO `book_settings` (
                `book_id`,
                `fit_mode`,
                `direction`,
                `trim_enabled`,
                `contrast`,
                `spread_mode`,
                `orientation`,
                `updated_at`
            ) VALUES ('$bookId', 'FitWidth', 'Rtl', 0, 1.25, 1, '$orientation', $updatedAt)
            """.trimIndent(),
        )
    }

    private data class ColumnSpec(
        val type: String,
        val notNull: Boolean,
        /** `PRAGMA table_info`의 dflt_value 원문 — 문자열 default는 따옴표 포함(`'Auto'`). */
        val defaultValue: String?,
        /** PK 안에서의 1-based 위치, PK가 아니면 0. */
        val primaryKeyPosition: Int,
    )

    private fun columnsOf(db: SupportSQLiteDatabase, table: String): Map<String, ColumnSpec> {
        val columns = linkedMapOf<String, ColumnSpec>()
        db.query("PRAGMA table_info(`$table`)").use { cursor ->
            val nameIdx = cursor.getColumnIndexOrThrow("name")
            val typeIdx = cursor.getColumnIndexOrThrow("type")
            val notNullIdx = cursor.getColumnIndexOrThrow("notnull")
            val defaultIdx = cursor.getColumnIndexOrThrow("dflt_value")
            val pkIdx = cursor.getColumnIndexOrThrow("pk")
            while (cursor.moveToNext()) {
                columns[cursor.getString(nameIdx)] = ColumnSpec(
                    type = cursor.getString(typeIdx),
                    notNull = cursor.getInt(notNullIdx) != 0,
                    defaultValue = if (cursor.isNull(defaultIdx)) null else cursor.getString(defaultIdx),
                    primaryKeyPosition = cursor.getInt(pkIdx),
                )
            }
        }
        return columns
    }

    private fun indexNamesOf(db: SupportSQLiteDatabase, table: String): Set<String> {
        val names = mutableSetOf<String>()
        db.query("PRAGMA index_list(`$table`)").use { cursor ->
            val nameIdx = cursor.getColumnIndexOrThrow("name")
            while (cursor.moveToNext()) {
                names += cursor.getString(nameIdx)
            }
        }
        return names
    }

    private fun tableNames(db: SupportSQLiteDatabase): Set<String> {
        val names = mutableSetOf<String>()
        db.query("SELECT `name` FROM `sqlite_master` WHERE `type` = 'table'").use { cursor ->
            while (cursor.moveToNext()) {
                names += cursor.getString(0)
            }
        }
        return names
    }

    /**
     * [version] 시점의 전체 schema를 손으로 적은 DDL로 만든다. 마이그레이션 코드를 재사용하지 않는 게 핵심 —
     * 재사용하면 마이그레이션이 틀려도 테스트가 같이 틀려서 통과한다.
     *
     * 출처: v1 position은 Room 도입 커밋의 PositionEntity, 나머지는 각 버전의 entity/마이그레이션 DDL.
     */
    private fun createSchema(db: SupportSQLiteDatabase, version: Int) {
        db.execSQL(if (version >= 3) POSITION_TABLE_V3 else POSITION_TABLE_V1)
        if (version >= 2) db.execSQL(bookSettingsTableFor(version))
        if (version >= 4) db.execSQL(COVER_META_TABLE)
        if (version >= 5) db.execSQL(BOOKMARK_TABLE)
        if (version >= 7) {
            db.execSQL(BOOK_INDEX_TABLE)
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_book_index_root_uri` ON `book_index` (`root_uri`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_book_index_group_key` ON `book_index` (`group_key`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_book_index_indexed_at` ON `book_index` (`indexed_at`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_bookmark_created_at` ON `bookmark` (`created_at`)")
        }
    }

    private fun bookSettingsTableFor(version: Int): String = when {
        version <= 5 -> BOOK_SETTINGS_TABLE_V2
        version <= 7 -> BOOK_SETTINGS_TABLE_V6
        version == 8 -> BOOK_SETTINGS_TABLE_V8
        version == 9 -> BOOK_SETTINGS_TABLE_V9
        version == 10 -> BOOK_SETTINGS_TABLE_V10
        version == 11 -> BOOK_SETTINGS_TABLE_V11
        else -> throw IllegalArgumentException("no hand-written book_settings DDL for v$version")
    }

    private companion object {
        const val DB_NAME = "migration-test.db"

        /** v1~v2: page_count 없음. */
        const val POSITION_TABLE_V1 = """
            CREATE TABLE IF NOT EXISTS `position` (
                `book_id` TEXT NOT NULL,
                `page_index` INTEGER NOT NULL,
                `updated_at` INTEGER NOT NULL,
                PRIMARY KEY(`book_id`)
            )
        """

        /** v3~: page_count 추가. */
        const val POSITION_TABLE_V3 = """
            CREATE TABLE IF NOT EXISTS `position` (
                `book_id` TEXT NOT NULL,
                `page_index` INTEGER NOT NULL,
                `page_count` INTEGER NOT NULL DEFAULT 0,
                `updated_at` INTEGER NOT NULL,
                PRIMARY KEY(`book_id`)
            )
        """

        /** v2~v5: 전역 prefs로 빠지기 전의 orphan 컬럼(invert_enabled/full_refresh_interval) 포함. */
        const val BOOK_SETTINGS_TABLE_V2 = """
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
        """

        /** v6~v7 (v7 = 1.0 릴리스). */
        const val BOOK_SETTINGS_TABLE_V6 = """
            CREATE TABLE IF NOT EXISTS `book_settings` (
                `book_id` TEXT NOT NULL,
                `fit_mode` TEXT NOT NULL,
                `direction` TEXT NOT NULL,
                `trim_enabled` INTEGER NOT NULL,
                `contrast` REAL NOT NULL,
                `updated_at` INTEGER NOT NULL,
                PRIMARY KEY(`book_id`)
            )
        """

        /** v8: spread_mode 추가. */
        const val BOOK_SETTINGS_TABLE_V8 = """
            CREATE TABLE IF NOT EXISTS `book_settings` (
                `book_id` TEXT NOT NULL,
                `fit_mode` TEXT NOT NULL,
                `direction` TEXT NOT NULL,
                `trim_enabled` INTEGER NOT NULL,
                `contrast` REAL NOT NULL,
                `updated_at` INTEGER NOT NULL,
                `spread_mode` INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY(`book_id`)
            )
        """

        /** v9 (v1.1 베타): orientation default가 'Auto'. */
        const val BOOK_SETTINGS_TABLE_V9 = """
            CREATE TABLE IF NOT EXISTS `book_settings` (
                `book_id` TEXT NOT NULL,
                `fit_mode` TEXT NOT NULL,
                `direction` TEXT NOT NULL,
                `trim_enabled` INTEGER NOT NULL,
                `contrast` REAL NOT NULL,
                `spread_mode` INTEGER NOT NULL DEFAULT 0,
                `orientation` TEXT NOT NULL DEFAULT 'Auto',
                `updated_at` INTEGER NOT NULL,
                PRIMARY KEY(`book_id`)
            )
        """

        /** v10: orientation default를 'Portrait'로 정규화. */
        const val BOOK_SETTINGS_TABLE_V10 = """
            CREATE TABLE IF NOT EXISTS `book_settings` (
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
        """

        /** v11: 모든 override 필드 nullable. */
        const val BOOK_SETTINGS_TABLE_V11 = """
            CREATE TABLE IF NOT EXISTS `book_settings` (
                `book_id` TEXT NOT NULL,
                `fit_mode` TEXT,
                `direction` TEXT,
                `trim_enabled` INTEGER,
                `contrast` REAL,
                `spread_mode` INTEGER,
                `orientation` TEXT,
                `updated_at` INTEGER NOT NULL,
                PRIMARY KEY(`book_id`)
            )
        """

        const val COVER_META_TABLE = """
            CREATE TABLE IF NOT EXISTS `cover_meta` (
                `book_id` TEXT NOT NULL,
                `status` TEXT NOT NULL,
                `source_page_index` INTEGER NOT NULL,
                `extracted_at` INTEGER NOT NULL,
                PRIMARY KEY(`book_id`)
            )
        """

        const val BOOKMARK_TABLE = """
            CREATE TABLE IF NOT EXISTS `bookmark` (
                `book_id` TEXT NOT NULL,
                `page_index` INTEGER NOT NULL,
                `created_at` INTEGER NOT NULL,
                PRIMARY KEY(`book_id`, `page_index`)
            )
        """

        const val BOOK_INDEX_TABLE = """
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
        """
    }
}
