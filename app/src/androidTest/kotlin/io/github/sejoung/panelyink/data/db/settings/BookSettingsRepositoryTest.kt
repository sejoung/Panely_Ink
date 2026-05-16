package io.github.sejoung.panelyink.data.db.settings

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.sejoung.panelyink.core.fit.FitMode
import io.github.sejoung.panelyink.core.preferences.ReaderOrientation
import io.github.sejoung.panelyink.core.preferences.ReadingDirection
import io.github.sejoung.panelyink.data.db.PanelyDatabase
import io.github.sejoung.panelyink.reader.model.BookSettingsOverrides
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * sparse-override repository 동작 검증.
 *
 * 핵심 invariant — v1.1 버그 수정의 본질:
 * 1. 빈 [BookSettingsOverrides]를 save하면 row 자체가 삭제되어야 한다. 그래야 다음 load가 null을 반환해
 *    [io.github.sejoung.panelyink.core.preferences.AppPreferences] 전역값이 그대로 적용된다.
 * 2. 일부 필드만 채운 sparse override는 그 필드만 저장되고 다른 필드는 NULL로 남아야 한다.
 * 3. load는 저장한 override를 그대로 복원해야 한다 (round-trip).
 */
@RunWith(AndroidJUnit4::class)
class BookSettingsRepositoryTest {

    private lateinit var db: PanelyDatabase
    private lateinit var repo: BookSettingsRepository

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            PanelyDatabase::class.java,
        )
            .allowMainThreadQueries()
            .build()
        repo = RoomBookSettingsRepository(db, clock = { 0L })
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun loadOfUnknownBookReturnsNull() = runBlocking {
        assertNull(repo.load("never-opened"))
    }

    @Test
    fun saveEmptyOverridesDoesNotCreateRow() = runBlocking {
        repo.save("book", BookSettingsOverrides.NONE)
        // 빈 override는 row 없는 상태와 동등 — 다음 load가 null이어야 전역 기본값이 깨끗하게 적용됨.
        assertNull(repo.load("book"))
    }

    @Test
    fun saveEmptyOverridesClearsExistingRow() = runBlocking {
        // 사용자가 한 번 override를 만들었다가 모두 되돌린 시나리오
        repo.save("book", BookSettingsOverrides(spreadMode = true))
        assertEquals(BookSettingsOverrides(spreadMode = true), repo.load("book"))

        repo.save("book", BookSettingsOverrides.NONE)
        assertNull(repo.load("book"))
    }

    @Test
    fun savePartialOverrideRoundTrips() = runBlocking {
        val partial = BookSettingsOverrides(spreadMode = true)
        repo.save("book", partial)

        val loaded = repo.load("book")
        assertEquals(partial, loaded)
        assertNull(loaded?.fitMode)
        assertNull(loaded?.direction)
        assertNull(loaded?.trimEnabled)
        assertNull(loaded?.contrast)
        assertNull(loaded?.orientation)
    }

    @Test
    fun saveFullOverrideRoundTrips() = runBlocking {
        val full = BookSettingsOverrides(
            fitMode = FitMode.FitWidth,
            direction = ReadingDirection.Rtl,
            trimEnabled = false,
            contrast = 1.3f,
            spreadMode = true,
            orientation = ReaderOrientation.Landscape,
        )
        repo.save("book", full)

        assertEquals(full, repo.load("book"))
    }

    @Test
    fun saveOverwritesPreviousOverrides() = runBlocking {
        repo.save("book", BookSettingsOverrides(spreadMode = true))
        repo.save("book", BookSettingsOverrides(orientation = ReaderOrientation.Landscape))

        // 가장 마지막 save가 진실. 이전 spreadMode override는 사라져야 한다.
        assertEquals(
            BookSettingsOverrides(orientation = ReaderOrientation.Landscape),
            repo.load("book"),
        )
    }

    @Test
    fun differentBooksHaveIndependentOverrides() = runBlocking {
        repo.save("a", BookSettingsOverrides(spreadMode = true))
        repo.save("b", BookSettingsOverrides(orientation = ReaderOrientation.Landscape))

        assertEquals(BookSettingsOverrides(spreadMode = true), repo.load("a"))
        assertEquals(BookSettingsOverrides(orientation = ReaderOrientation.Landscape), repo.load("b"))
    }
}
