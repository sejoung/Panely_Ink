package io.github.sejoung.panelyink.data.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BookmarkDaoTest {

    private lateinit var db: PanelyDatabase
    private lateinit var repo: BookmarkRepository
    private var now: Long = 1000L

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            PanelyDatabase::class.java,
        )
            .allowMainThreadQueries()
            .build()
        repo = RoomBookmarkRepository(db, clock = { now })
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun addLoadAndRemoveBookmark() = runBlocking {
        repo.add("book", 3)

        assertTrue(repo.isBookmarked("book", 3))
        assertFalse(repo.isBookmarked("book", 4))
        assertEquals(listOf(3), repo.loadPages("book"))

        repo.remove("book", 3)

        assertFalse(repo.isBookmarked("book", 3))
        assertEquals(emptyList<Int>(), repo.loadPages("book"))
    }

    @Test
    fun loadPagesOrdersByPageIndex() = runBlocking {
        repo.add("book", 10)
        repo.add("book", 2)
        repo.add("book", 5)

        assertEquals(listOf(2, 5, 10), repo.loadPages("book"))
    }

    @Test
    fun samePageBookmarkIsIdempotent() = runBlocking {
        repo.add("book", 7)
        repo.add("book", 7)

        assertEquals(listOf(7), repo.loadPages("book"))
    }

    @Test
    fun loadAllOrdersByCreatedAtDesc() = runBlocking {
        now = 1000L
        repo.add("a", 1)
        now = 2000L
        repo.add("b", 2)

        assertEquals(
            listOf(
                BookBookmark(bookId = "b", pageIndex = 2, createdAt = 2000L),
                BookBookmark(bookId = "a", pageIndex = 1, createdAt = 1000L),
            ),
            repo.loadAll(),
        )
    }

    @Test
    fun removeOrphansKeepsExistingBooksOnly() = runBlocking {
        repo.add("kept", 1)
        repo.add("deleted", 2)

        repo.removeOrphans(setOf("kept"))

        assertEquals(listOf(1), repo.loadPages("kept"))
        assertEquals(emptyList<Int>(), repo.loadPages("deleted"))
    }

    @Test
    fun removeOrphansWithNoExistingBooksClearsAll() = runBlocking {
        repo.add("a", 1)
        repo.add("b", 2)

        repo.removeOrphans(emptySet())

        assertEquals(emptyList<BookBookmark>(), repo.loadAll())
    }
}
