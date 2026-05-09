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
            .addMigrations(PanelyDatabase.MIGRATION_5_6)
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

    private companion object {
        const val DB_NAME = "migration-test.db"
    }
}
