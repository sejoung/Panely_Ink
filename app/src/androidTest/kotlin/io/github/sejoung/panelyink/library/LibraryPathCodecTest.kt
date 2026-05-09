package io.github.sejoung.panelyink.library

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
