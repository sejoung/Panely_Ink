package io.github.sejoung.panelyink.core.archive

import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import org.apache.commons.compress.archivers.zip.ZipFile
import org.apache.commons.compress.utils.SeekableInMemoryByteChannel
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException

class CbzArchiveTest {

  @Test
  fun readBytesLimitedReturnsBytesWhenInputFitsLimit() {
    val inputBytes = ByteArray(32) { it.toByte() }

    val result = CbzArchive.readBytesLimited(
      input = ByteArrayInputStream(inputBytes),
      limitBytes = inputBytes.size.toLong(),
    )

    assertArrayEquals(inputBytes, result)
  }

  @Test(expected = IOException::class)
  fun readBytesLimitedThrowsWhenInputExceedsLimit() {
    CbzArchive.readBytesLimited(
      input = ByteArrayInputStream(ByteArray(33)),
      limitBytes = 32L,
    )
  }

  @Test
  fun duplicateEntryNamesOpenTheirOwnContent() {
    // 같은 이름의 entry 2개 — getEntry(name)이면 둘 다 첫 entry가 열린다.
    val zip = buildZip(
      encoding = "UTF-8",
      "001.jpg" to byteArrayOf(1, 1, 1),
      "001.jpg" to byteArrayOf(2, 2, 2, 2),
    )

    CbzArchive.openChannels(listOf(SeekableInMemoryByteChannel(zip))).use { archive ->
      assertEquals(2, archive.pages.size)
      val contents = archive.pages.indices.map { i -> archive.openPage(i).use { it.readBytes() } }
      assertEquals(
        setOf(listOf<Byte>(1, 1, 1), listOf<Byte>(2, 2, 2, 2)),
        contents.map { it.toList() }.toSet(),
      )
    }
  }

  @Test
  fun cp949NamesWithoutUtf8FlagAreDecodedAndStayDistinct() {
    // UTF-8 플래그 없는 CP949 zip — UTF-8로 디코드하면 두 폴더가 같은 깨진 이름이 된다.
    val zip = buildZip(
      encoding = "x-windows-949",
      "하/001.jpg" to byteArrayOf(20),
      "상/001.jpg" to byteArrayOf(10),
    )

    CbzArchive.openChannels(listOf(SeekableInMemoryByteChannel(zip))).use { archive ->
      assertEquals(listOf("상/001.jpg", "하/001.jpg"), archive.pages.map { it.name })
      assertArrayEquals(byteArrayOf(10), archive.openPage(0).use { it.readBytes() })
      assertArrayEquals(byteArrayOf(20), archive.openPage(1).use { it.readBytes() })
    }
  }

  @Test
  fun nestedEntryOpensByDecodedNameAndByLegacyName() {
    val zip = buildZip(
      encoding = "x-windows-949",
      "만화 1권.cbz" to byteArrayOf(1),
      "만화 2권.cbz" to byteArrayOf(2),
    )
    // 이전 버전이 저장해 둔 이름 = Commons Compress가 UTF-8로 디코드한 깨진 이름.
    val legacyName = ZipFile.builder()
      .setSeekableByteChannel(SeekableInMemoryByteChannel(zip))
      .get()
      .use { it.entries.nextElement().name }
    assertNotEquals("만화 1권.cbz", legacyName)

    CbzArchive.openChannels(listOf(SeekableInMemoryByteChannel(zip))).use { archive ->
      // 표시 이름은 보정되지만 식별자(entryName → bookId)는 이전 버전과 같은 이름을 유지한다 —
      // 바뀌면 기존 진행 위치/북마크가 고아가 된다.
      assertEquals(listOf("만화 1권.cbz", "만화 2권.cbz"), archive.nestedArchives.map { it.displayName })
      assertEquals(legacyName, archive.nestedArchives.first().entryName)
      assertEquals(1L, archive.nestedEntrySize(legacyName))
      assertArrayEquals(byteArrayOf(2), archive.openNestedEntry("만화 2권.cbz").use { it.readBytes() })
      assertArrayEquals(byteArrayOf(1), archive.openNestedEntry(legacyName).use { it.readBytes() })
      assertEquals(1L, archive.nestedEntrySize("만화 2권.cbz"))
    }
  }

  @Test
  fun everyPooledReaderResolvesTheSameEntry() {
    val zip = buildZip(
      encoding = "UTF-8",
      "a/001.jpg" to byteArrayOf(1),
      "a/001.jpg" to byteArrayOf(2),
    )

    CbzArchive.openChannels(
      listOf(SeekableInMemoryByteChannel(zip), SeekableInMemoryByteChannel(zip)),
    ).use { archive ->
      // 두 stream을 동시에 열어 풀의 reader 2개를 모두 쓴다.
      archive.openPage(1).use { first ->
        archive.openPage(1).use { second ->
          assertArrayEquals(first.readBytes(), second.readBytes())
        }
      }
    }
  }

  private fun buildZip(encoding: String, vararg entries: Pair<String, ByteArray>): ByteArray {
    val bytes = ByteArrayOutputStream()
    ZipArchiveOutputStream(bytes).use { zos ->
      zos.setEncoding(encoding)
      zos.setUseLanguageEncodingFlag(false)
      entries.forEach { (name, content) ->
        zos.putArchiveEntry(ZipArchiveEntry(name))
        zos.write(content)
        zos.closeArchiveEntry()
      }
    }
    return bytes.toByteArray()
  }
}
