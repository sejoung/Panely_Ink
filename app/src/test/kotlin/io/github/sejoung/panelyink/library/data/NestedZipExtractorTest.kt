package io.github.sejoung.panelyink.library.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException

class NestedZipExtractorTest {

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
}
