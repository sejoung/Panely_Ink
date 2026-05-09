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

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            PanelyDatabase::class.java,
        )
            .allowMainThreadQueries()
            .build()
        repo = RoomBookmarkRepository(db, clock = { 1000L })
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
}
