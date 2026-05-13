package io.github.sejoung.panelyink.core.archive

import org.junit.Assert.assertArrayEquals
import org.junit.Test
import java.io.ByteArrayInputStream
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
}
