package io.github.sejoung.panelyink.data.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

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

    @Test
    fun migration5To6DropsBookSettingsOrphanColumnsAndKeepsData() {
        createVersion5Database()

        val roomDb = Room.databaseBuilder(context, PanelyDatabase::class.java, DB_NAME)
            .addMigrations(PanelyDatabase.MIGRATION_5_6, PanelyDatabase.MIGRATION_6_7)
            .allowMainThreadQueries()
            .build()

        val db = roomDb.openHelper.readableDatabase
        db.query("PRAGMA table_info(`book_settings`)").use { cursor ->
            val names = mutableSetOf<String>()
            val nameIndex = cursor.getColumnIndexOrThrow("name")
            while (cursor.moveToNext()) {
                names += cursor.getString(nameIndex)
            }
            assertEquals(
                setOf("book_id", "fit_mode", "direction", "trim_enabled", "contrast", "updated_at"),
                names,
            )
            assertFalse("invert_enabled should be removed", "invert_enabled" in names)
            assertFalse("full_refresh_interval should be removed", "full_refresh_interval" in names)
        }

        db.query("SELECT * FROM `book_settings` WHERE `book_id` = 'book'").use { cursor ->
            assertEquals(true, cursor.moveToFirst())
            assertEquals("FitWidth", cursor.getString(cursor.getColumnIndexOrThrow("fit_mode")))
            assertEquals("Rtl", cursor.getString(cursor.getColumnIndexOrThrow("direction")))
            assertEquals(0, cursor.getInt(cursor.getColumnIndexOrThrow("trim_enabled")))
            assertEquals(1.25f, cursor.getFloat(cursor.getColumnIndexOrThrow("contrast")))
            assertEquals(1234L, cursor.getLong(cursor.getColumnIndexOrThrow("updated_at")))
        }

        roomDb.close()
    }

    @Test
    fun migration9To10NormalizesAutoOrientationToPortrait() {
        // v1.1 베타 단계에서 orientation 컬럼이 'Auto' default로 만들어졌고, 행 데이터에도
        // 'Auto'가 박혀 있을 수 있다. v10 마이그레이션이 'Portrait'로 정규화하는지 검증.
        createVersion9DatabaseWithAutoRow()

        val roomDb = Room.databaseBuilder(context, PanelyDatabase::class.java, DB_NAME)
            .addMigrations(PanelyDatabase.MIGRATION_9_10, PanelyDatabase.MIGRATION_10_11)
            .allowMainThreadQueries()
            .build()

        val db = roomDb.openHelper.readableDatabase
        db.query("SELECT `orientation` FROM `book_settings` WHERE `book_id` = 'legacy'").use { cursor ->
            assertEquals(true, cursor.moveToFirst())
            assertEquals("Portrait", cursor.getString(0))
        }

        roomDb.close()
    }

    @Test
    fun migration10To11MakesSettingsColumnsNullableAndPreservesData() {
        // v10에서 NOT NULL이었던 6필드가 v11에서 nullable로. 기존 행은 그대로 보존되어야 한다.
        createVersion10Database()

        val roomDb = Room.databaseBuilder(context, PanelyDatabase::class.java, DB_NAME)
            .addMigrations(PanelyDatabase.MIGRATION_10_11)
            .allowMainThreadQueries()
            .build()

        val db = roomDb.openHelper.readableDatabase

        // schema: 6필드 모두 notnull=0 (nullable)이어야 한다. book_id와 updated_at만 NOT NULL 유지.
        val notNullByColumn = mutableMapOf<String, Int>()
        db.query("PRAGMA table_info(`book_settings`)").use { cursor ->
            val nameIdx = cursor.getColumnIndexOrThrow("name")
            val notNullIdx = cursor.getColumnIndexOrThrow("notnull")
            while (cursor.moveToNext()) {
                notNullByColumn[cursor.getString(nameIdx)] = cursor.getInt(notNullIdx)
            }
        }
        assertEquals(1, notNullByColumn["book_id"])
        assertEquals(1, notNullByColumn["updated_at"])
        assertEquals(0, notNullByColumn["fit_mode"])
        assertEquals(0, notNullByColumn["direction"])
        assertEquals(0, notNullByColumn["trim_enabled"])
        assertEquals(0, notNullByColumn["contrast"])
        assertEquals(0, notNullByColumn["spread_mode"])
        assertEquals(0, notNullByColumn["orientation"])

        // 기존 row 보존 — 기존 책은 '전 필드 명시 override' 상태로 유지되어 동작이 깨지지 않음.
        db.query("SELECT * FROM `book_settings` WHERE `book_id` = 'legacy'").use { cursor ->
            assertEquals(true, cursor.moveToFirst())
            assertEquals("FitWidth", cursor.getString(cursor.getColumnIndexOrThrow("fit_mode")))
            assertEquals("Rtl", cursor.getString(cursor.getColumnIndexOrThrow("direction")))
            assertEquals(0, cursor.getInt(cursor.getColumnIndexOrThrow("trim_enabled")))
            assertEquals(1.25f, cursor.getFloat(cursor.getColumnIndexOrThrow("contrast")))
            assertEquals(1, cursor.getInt(cursor.getColumnIndexOrThrow("spread_mode")))
            assertEquals("Landscape", cursor.getString(cursor.getColumnIndexOrThrow("orientation")))
            assertEquals(2222L, cursor.getLong(cursor.getColumnIndexOrThrow("updated_at")))
        }

        // 새 책의 sparse insert가 가능한지 (모든 가변 컬럼 NULL) — 이게 깨지면 sparse override 모델 자체가 작동 안 함.
        roomDb.openHelper.writableDatabase.execSQL(
            "INSERT INTO `book_settings` (`book_id`, `updated_at`) VALUES ('sparse', 3333)",
        )
        db.query("SELECT `fit_mode`, `orientation` FROM `book_settings` WHERE `book_id` = 'sparse'").use { cursor ->
            assertEquals(true, cursor.moveToFirst())
            assertEquals(true, cursor.isNull(0))
            assertEquals(true, cursor.isNull(1))
        }

        roomDb.close()
    }

    @Test
    fun migration11To12AddsNullableCoverAloneColumnAndPreservesData() {
        createVersion11Database()

        val roomDb = Room.databaseBuilder(context, PanelyDatabase::class.java, DB_NAME)
            .addMigrations(PanelyDatabase.MIGRATION_11_12)
            .allowMainThreadQueries()
            .build()

        val db = roomDb.openHelper.readableDatabase

        // cover_alone 컬럼이 nullable로 추가되었는지.
        var coverAloneNotNull: Int? = null
        db.query("PRAGMA table_info(`book_settings`)").use { cursor ->
            val nameIdx = cursor.getColumnIndexOrThrow("name")
            val notNullIdx = cursor.getColumnIndexOrThrow("notnull")
            while (cursor.moveToNext()) {
                if (cursor.getString(nameIdx) == "cover_alone") {
                    coverAloneNotNull = cursor.getInt(notNullIdx)
                }
            }
        }
        assertEquals(0, coverAloneNotNull)

        // 기존 행은 보존되고 cover_alone은 NULL(미설정 → 진입 시 false 합성).
        db.query("SELECT * FROM `book_settings` WHERE `book_id` = 'legacy'").use { cursor ->
            assertEquals(true, cursor.moveToFirst())
            assertEquals("FitWidth", cursor.getString(cursor.getColumnIndexOrThrow("fit_mode")))
            assertEquals(1, cursor.getInt(cursor.getColumnIndexOrThrow("spread_mode")))
            assertEquals(true, cursor.isNull(cursor.getColumnIndexOrThrow("cover_alone")))
            assertEquals(2222L, cursor.getLong(cursor.getColumnIndexOrThrow("updated_at")))
        }

        roomDb.close()
    }

    @Test
    fun migration6To7AddsBookIndexAndBookmarkCreatedAtIndex() {
        createVersion6Database()

        val roomDb = Room.databaseBuilder(context, PanelyDatabase::class.java, DB_NAME)
            .addMigrations(PanelyDatabase.MIGRATION_6_7)
            .allowMainThreadQueries()
            .build()

        val db = roomDb.openHelper.readableDatabase
        db.query("PRAGMA table_info(`book_index`)").use { cursor ->
            val names = mutableSetOf<String>()
            val nameIndex = cursor.getColumnIndexOrThrow("name")
            while (cursor.moveToNext()) {
                names += cursor.getString(nameIndex)
            }
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
                names,
            )
        }
        db.query("PRAGMA index_list(`bookmark`)").use { cursor ->
            val names = mutableSetOf<String>()
            val nameIndex = cursor.getColumnIndexOrThrow("name")
            while (cursor.moveToNext()) {
                names += cursor.getString(nameIndex)
            }
            assertEquals(true, "index_bookmark_created_at" in names)
        }

        roomDb.close()
    }

    private fun createVersion5Database() {
        val file = context.getDatabasePath(DB_NAME)
        file.parentFile?.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            db.version = 5
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `position` (
                    `book_id` TEXT NOT NULL,
                    `page_index` INTEGER NOT NULL,
                    `page_count` INTEGER NOT NULL DEFAULT 0,
                    `updated_at` INTEGER NOT NULL,
                    PRIMARY KEY(`book_id`)
                )
                """.trimIndent(),
            )
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
        }
    }

    private fun createVersion9DatabaseWithAutoRow() {
        val file = context.getDatabasePath(DB_NAME)
        file.parentFile?.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            db.version = 9
            db.execSQL(EMPTY_POSITION_TABLE)
            db.execSQL(
                """
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
                """.trimIndent(),
            )
            db.execSQL(EMPTY_COVER_META_TABLE)
            db.execSQL(EMPTY_BOOKMARK_TABLE)
            db.execSQL(EMPTY_BOOK_INDEX_TABLE)
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
                ) VALUES ('legacy', 'FitScreen', 'Ltr', 1, 1.0, 0, 'Auto', 1111)
                """.trimIndent(),
            )
        }
    }

    private fun createVersion10Database() {
        val file = context.getDatabasePath(DB_NAME)
        file.parentFile?.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            db.version = 10
            db.execSQL(EMPTY_POSITION_TABLE)
            db.execSQL(
                """
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
                """.trimIndent(),
            )
            db.execSQL(EMPTY_COVER_META_TABLE)
            db.execSQL(EMPTY_BOOKMARK_TABLE)
            db.execSQL(EMPTY_BOOK_INDEX_TABLE)
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
                ) VALUES ('legacy', 'FitWidth', 'Rtl', 0, 1.25, 1, 'Landscape', 2222)
                """.trimIndent(),
            )
        }
    }

    private fun createVersion11Database() {
        val file = context.getDatabasePath(DB_NAME)
        file.parentFile?.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            db.version = 11
            db.execSQL(EMPTY_POSITION_TABLE)
            // v11: 모든 override 필드 nullable.
            db.execSQL(
                """
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
                """.trimIndent(),
            )
            db.execSQL(EMPTY_COVER_META_TABLE)
            db.execSQL(EMPTY_BOOKMARK_TABLE)
            db.execSQL(EMPTY_BOOK_INDEX_TABLE)
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
                ) VALUES ('legacy', 'FitWidth', 'Rtl', 0, 1.25, 1, 'Landscape', 2222)
                """.trimIndent(),
            )
        }
    }

    private fun createVersion6Database() {
        val file = context.getDatabasePath(DB_NAME)
        file.parentFile?.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            db.version = 6
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `position` (
                    `book_id` TEXT NOT NULL,
                    `page_index` INTEGER NOT NULL,
                    `page_count` INTEGER NOT NULL DEFAULT 0,
                    `updated_at` INTEGER NOT NULL,
                    PRIMARY KEY(`book_id`)
                )
                """.trimIndent(),
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `book_settings` (
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
                CREATE TABLE IF NOT EXISTS `cover_meta` (
                    `book_id` TEXT NOT NULL,
                    `status` TEXT NOT NULL,
                    `source_page_index` INTEGER NOT NULL,
                    `extracted_at` INTEGER NOT NULL,
                    PRIMARY KEY(`book_id`)
                )
                """.trimIndent(),
            )
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

    private companion object {
        const val DB_NAME = "migration-test.db"

        // 마이그레이션 테스트에서 다른 테이블이 필요하지만 본 검증과 무관할 때 쓰는 빈 스키마 DDL.
        const val EMPTY_POSITION_TABLE = """
            CREATE TABLE IF NOT EXISTS `position` (
                `book_id` TEXT NOT NULL,
                `page_index` INTEGER NOT NULL,
                `page_count` INTEGER NOT NULL DEFAULT 0,
                `updated_at` INTEGER NOT NULL,
                PRIMARY KEY(`book_id`)
            )
        """

        const val EMPTY_COVER_META_TABLE = """
            CREATE TABLE IF NOT EXISTS `cover_meta` (
                `book_id` TEXT NOT NULL,
                `status` TEXT NOT NULL,
                `source_page_index` INTEGER NOT NULL,
                `extracted_at` INTEGER NOT NULL,
                PRIMARY KEY(`book_id`)
            )
        """

        const val EMPTY_BOOKMARK_TABLE = """
            CREATE TABLE IF NOT EXISTS `bookmark` (
                `book_id` TEXT NOT NULL,
                `page_index` INTEGER NOT NULL,
                `created_at` INTEGER NOT NULL,
                PRIMARY KEY(`book_id`, `page_index`)
            )
        """

        const val EMPTY_BOOK_INDEX_TABLE = """
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
