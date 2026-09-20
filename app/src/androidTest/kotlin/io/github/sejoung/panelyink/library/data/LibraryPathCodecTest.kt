package io.github.sejoung.panelyink.library.data

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.sejoung.panelyink.library.model.BookEntry
import io.github.sejoung.panelyink.library.model.FolderEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LibraryPathCodecTest {

    @Test
    fun encodeDecodeRoundTrip() {
        val root = Uri.parse("content://root")
        val path = listOf(
            FolderEntry(root, "Root", root, isRoot = true),
            FolderEntry(Uri.parse("content://root/series"), "Series", root, isRoot = false),
        )

        assertEquals(path, LibraryPathCodec.decode(LibraryPathCodec.encode(path)))
    }

    @Test
    fun virtualZipFolderKeepsMarkerButNotNestedBooks() {
        val root = Uri.parse("content://root")
        val zip = Uri.parse("content://root/series.zip")
        val nested = BookEntry(
            documentUri = zip,
            displayName = "01.cbz",
            sizeBytes = 10L,
            mimeType = null,
            rootUri = root,
            nestedEntryName = "01.cbz",
        )
        val path = listOf(
            FolderEntry(root, "Root", root, isRoot = true),
            FolderEntry(zip, "series.zip", root, isRoot = false, nestedBooks = listOf(nested)),
        )

        val decoded = LibraryPathCodec.decode(LibraryPathCodec.encode(path))

        // 일반 폴더는 null, 가상 폴더는 빈 목록(=복원 시 재검사 필요 표식).
        assertNull(decoded[0].nestedBooks)
        assertEquals(zip, decoded[1].documentUri)
        assertEquals(emptyList<BookEntry>(), decoded[1].nestedBooks)
    }

    @Test
    fun legacyJsonWithoutVirtualMarkerDecodesAsPlainFolder() {
        val legacy = """[{"name":"Root","doc":"content://root","root":"content://root","isRoot":true}]"""

        val decoded = LibraryPathCodec.decode(legacy)

        val root = Uri.parse("content://root")
        assertEquals(listOf(FolderEntry(root, "Root", root, isRoot = true)), decoded)
    }

    @Test
    fun invalidJsonFallsBackToEmptyPath() {
        assertEquals(emptyList<FolderEntry>(), LibraryPathCodec.decode("{broken"))
    }

    @Test
    fun pathIsValidWhenAllEntriesBelongToKnownRoot() {
        val root = Uri.parse("content://root")
        val path = listOf(
            FolderEntry(root, "Root", root, isRoot = true),
            FolderEntry(Uri.parse("content://root/a"), "A", root, isRoot = false),
        )

        assertTrue(LibraryPathCodec.isValid(path, listOf(root)))
    }

    @Test
    fun emptyPathIsInvalid() {
        assertFalse(LibraryPathCodec.isValid(emptyList(), listOf(Uri.parse("content://root"))))
    }

    @Test
    fun pathWithRemovedRootIsInvalid() {
        val root = Uri.parse("content://root")
        val path = listOf(FolderEntry(root, "Root", root, isRoot = true))

        assertFalse(LibraryPathCodec.isValid(path, listOf(Uri.parse("content://other"))))
    }

    @Test
    fun pathWithMixedRootsIsInvalid() {
        val root = Uri.parse("content://root")
        val other = Uri.parse("content://other")
        val path = listOf(
            FolderEntry(root, "Root", root, isRoot = true),
            FolderEntry(Uri.parse("content://other/a"), "A", other, isRoot = false),
        )

        assertFalse(LibraryPathCodec.isValid(path, listOf(root, other)))
    }
}
