package io.github.sejoung.panelyink.library.data

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.sejoung.panelyink.library.model.BookEntry
import io.github.sejoung.panelyink.library.model.FolderEntry
import io.github.sejoung.panelyink.library.model.bookId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `CoverState.visibleBookIdsFor` 검증. `android.net.Uri` / `BookEntry.bookId` 의존이 있어
 * androidTest 트랙. 순수 Map 연산은 `CoverStateTest`(JVM)에서 검증.
 *
 * 회귀 방어 포인트: 폴더 이동 시 `pruneCoversToVisible`이 사용하는 visible 집합이
 * 실제로 화면에 보일 책의 표지를 모두 포함하는지 — 누락되면 표지가 사라지고 다시 로드된다.
 */
@RunWith(AndroidJUnit4::class)
class CoverStateAndroidTest {

    @Test
    fun visibleBookIdsFor_directBooksAreIncluded() {
        val book1 = bookEntry("content://uri/book1.cbz")
        val book2 = bookEntry("content://uri/book2.cbz")
        val ids = CoverState.visibleBookIdsFor(
            entries = listOf(book1, book2),
            folderFirstBook = emptyMap(),
        )
        assertEquals(
            setOf(book1.bookId.value, book2.bookId.value),
            ids,
        )
    }

    @Test
    fun visibleBookIdsFor_folderFirstBookIsIncluded() {
        val folderUri = Uri.parse("content://uri/folder")
        val rootUri = Uri.parse("content://root")
        val folder = FolderEntry(
            documentUri = folderUri,
            displayName = "folder",
            rootUri = rootUri,
            isRoot = false,
        )
        val firstBookId = "first-book-id-hash"
        val ids = CoverState.visibleBookIdsFor(
            entries = listOf(folder),
            folderFirstBook = mapOf(folderUri to firstBookId),
        )
        assertEquals(setOf(firstBookId), ids)
    }

    @Test
    fun visibleBookIdsFor_virtualFolderFirstNestedBookIsIncluded() {
        // ZIP-of-CBZ 가상 폴더 — folderFirstBook 매핑이 없어도 nestedBooks 첫 권은 보존.
        val parentUri = Uri.parse("content://uri/series.zip")
        val rootUri = Uri.parse("content://root")
        val firstNested = BookEntry(
            documentUri = parentUri,
            displayName = "vol1.cbz",
            sizeBytes = 100,
            mimeType = null,
            rootUri = rootUri,
            nestedEntryName = "vol1.cbz",
        )
        val secondNested = firstNested.copy(
            displayName = "vol2.cbz",
            nestedEntryName = "vol2.cbz",
        )
        val virtualFolder = FolderEntry(
            documentUri = parentUri,
            displayName = "series",
            rootUri = rootUri,
            isRoot = false,
            nestedBooks = listOf(firstNested, secondNested),
        )
        val ids = CoverState.visibleBookIdsFor(
            entries = listOf(virtualFolder),
            folderFirstBook = emptyMap(),
        )
        // 첫 권만 보존 — 폴더 행은 표지 1장만 보여주므로.
        assertEquals(setOf(firstNested.bookId.value), ids)
    }

    @Test
    fun visibleBookIdsFor_emptyEntriesReturnsEmpty() {
        assertTrue(
            CoverState.visibleBookIdsFor(emptyList(), emptyMap()).isEmpty(),
        )
    }

    @Test
    fun visibleBookIdsFor_mixedFoldersAndBooks() {
        // 실제 라이브러리 폴더 — 책 + 폴더 + 가상 폴더 혼합.
        val rootUri = Uri.parse("content://root")
        val directBook = bookEntry("content://uri/direct.cbz")
        val plainFolder = FolderEntry(
            documentUri = Uri.parse("content://uri/folder1"),
            displayName = "folder1",
            rootUri = rootUri,
            isRoot = false,
        )
        val virtualFolder = FolderEntry(
            documentUri = Uri.parse("content://uri/series.zip"),
            displayName = "series",
            rootUri = rootUri,
            isRoot = false,
            nestedBooks = listOf(
                BookEntry(
                    documentUri = Uri.parse("content://uri/series.zip"),
                    displayName = "vol1.cbz",
                    sizeBytes = 100,
                    mimeType = null,
                    rootUri = rootUri,
                    nestedEntryName = "vol1.cbz",
                ),
            ),
        )
        val ids = CoverState.visibleBookIdsFor(
            entries = listOf(directBook, plainFolder, virtualFolder),
            folderFirstBook = mapOf(plainFolder.documentUri to "folder1-first-book-id"),
        )
        assertEquals(3, ids.size)
        assertTrue("direct book missing", ids.contains(directBook.bookId.value))
        assertTrue("folder firstBook missing", ids.contains("folder1-first-book-id"))
        assertTrue(
            "virtual folder first nested missing",
            ids.contains(virtualFolder.nestedBooks!!.first().bookId.value),
        )
    }

    private fun bookEntry(uriString: String): BookEntry = BookEntry(
        documentUri = Uri.parse(uriString),
        displayName = uriString.substringAfterLast('/'),
        sizeBytes = 0,
        mimeType = null,
        rootUri = Uri.parse("content://root"),
    )
}
