package io.github.sejoung.panelyink.library.data

import io.github.sejoung.panelyink.core.archive.NestedZipExtractor
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.concurrent.CancellationException

class NestedZipExtractorTest {

  @get:Rule
  val tmp = TemporaryFolder()

  @Test
  fun copyToLimitedCopiesWhenInputFitsLimit() {
    val inputBytes = ByteArray(32) { it.toByte() }
    val output = ByteArrayOutputStream()

    val copied = NestedZipExtractor.copyToLimited(
      input = ByteArrayInputStream(inputBytes),
      output = output,
      limitBytes = inputBytes.size.toLong(),
    )

    assertEquals(inputBytes.size.toLong(), copied)
    assertArrayEquals(inputBytes, output.toByteArray())
  }

  @Test(expected = IOException::class)
  fun copyToLimitedThrowsWhenInputExceedsLimit() {
    NestedZipExtractor.copyToLimited(
      input = ByteArrayInputStream(ByteArray(33)),
      output = ByteArrayOutputStream(),
      limitBytes = 32L,
    )
  }

  @Test
  fun copyToLimitedStopsWhenCancelled() {
    val output = ByteArrayOutputStream()
    var checks = 0

    val thrown = runCatching {
      NestedZipExtractor.copyToLimited(
        input = ByteArrayInputStream(ByteArray(DEFAULT_BUFFER_SIZE * 4)),
        output = output,
        limitBytes = Long.MAX_VALUE,
      ) {
        if (++checks > 2) throw CancellationException("cancelled")
      }
    }.exceptionOrNull()

    assertTrue(thrown is CancellationException)
    assertEquals(DEFAULT_BUFFER_SIZE * 2, output.size())
  }

  @Test
  fun writeAtomicallyRenamesTempToTarget() {
    val target = File(tmp.root, "nested/a.cbz")

    NestedZipExtractor.writeAtomically(target) { output ->
      // 쓰는 동안 최종 경로에는 아무것도 없어야 한다 — 다른 호출자가 쓰다 만 파일을 보지 않게.
      assertFalse(target.exists())
      output.write(byteArrayOf(1, 2, 3))
    }

    assertArrayEquals(byteArrayOf(1, 2, 3), target.readBytes())
    assertEquals(listOf("a.cbz"), target.parentFile!!.list()!!.toList())
  }

  @Test
  fun writeAtomicallyLeavesNothingOnFailure() {
    val target = File(tmp.root, "nested/a.cbz")

    val thrown = runCatching {
      NestedZipExtractor.writeAtomically(target) { output ->
        output.write(byteArrayOf(1, 2, 3))
        throw IOException("boom")
      }
    }.exceptionOrNull()

    assertTrue(thrown is IOException)
    assertFalse(target.exists())
    assertEquals(emptyList<String>(), target.parentFile!!.list()!!.toList())
  }

  @Test
  fun touchIfCachedRefreshesLastModifiedOnHit() {
    val target = tmp.newFile("a.cbz").apply { writeBytes(byteArrayOf(1)) }
    target.setLastModified(1_000_000L)

    assertTrue(NestedZipExtractor.touchIfCached(target))

    assertTrue(target.lastModified() > 1_000_000L)
  }

  @Test
  fun touchIfCachedMissesEmptyOrAbsentFile() {
    assertFalse(NestedZipExtractor.touchIfCached(tmp.newFile("empty.cbz")))
    assertFalse(NestedZipExtractor.touchIfCached(File(tmp.root, "absent.cbz")))
  }

  @Test
  fun pruneDirDeletesLeastRecentlyUsedAndStaleTemps() {
    val old = tmp.newFile("old.cbz").apply { writeBytes(ByteArray(10)) }
    val recent = tmp.newFile("recent.cbz").apply { writeBytes(ByteArray(10)) }
    val staleTemp = tmp.newFile("dead.cbz.1234.tmp").apply { writeBytes(ByteArray(10)) }
    // 먼저 추출된 쪽이 더 최근에 읽혔다 — hit 때 touch 하므로 나중에 추출된 쪽이 지워져야 한다.
    old.setLastModified(1_000_000L)
    recent.setLastModified(2_000_000L)
    NestedZipExtractor.touchIfCached(old)

    val deleted = NestedZipExtractor.pruneDir(tmp.root, keep = null, maxBytes = 10)

    assertEquals(2, deleted)
    assertTrue(old.exists())
    assertFalse(recent.exists())
    assertFalse(staleTemp.exists())
  }
}
