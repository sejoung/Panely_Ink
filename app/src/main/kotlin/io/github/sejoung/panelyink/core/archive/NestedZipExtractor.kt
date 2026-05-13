package io.github.sejoung.panelyink.core.archive

import android.content.Context
import android.net.Uri
import android.util.Log
import io.github.sejoung.panelyink.core.position.PositionKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * ZIP-of-CBZ에서 nested entry를 단일 cbz 파일로 추출한다.
 *
 * Apache Commons Compress가 nested entry에 대해 random access를 지원하지 않아(streaming만),
 * 임시 파일로 1회 추출 후 그 파일을 일반 [CbzArchive]로 연다.
 */
object NestedZipExtractor {

  private const val DIR = "nested"
  private const val EXT = "cbz"
  private const val TAG = "PanelyInk.NestedExtractor"

  suspend fun extract(
    context: Context,
    parentUri: Uri,
    entryName: String,
  ): File = withContext(Dispatchers.IO) {
    val target = cacheFile(context, parentUri, entryName)
    if (target.exists() && target.length() > 0) {
      Log.d(TAG, "cache hit: ${target.name}")
      return@withContext target
    }
    target.parentFile?.mkdirs()

    Log.d(TAG, "extract start: parent=$parentUri entry=$entryName")
    val t0 = System.currentTimeMillis()
    val archive = CbzArchive.open(context, parentUri)
    try {
      val declaredSize = archive.nestedEntrySize(entryName)
      if (declaredSize > MAX_NESTED_ARCHIVE_BYTES) {
        throw IOException(
          "nested archive too large: $declaredSize > $MAX_NESTED_ARCHIVE_BYTES",
        )
      }
      archive.openNestedEntry(entryName).use { input ->
        FileOutputStream(target).use { output ->
          copyToLimited(input, output, MAX_NESTED_ARCHIVE_BYTES)
        }
      }
    } catch (t: Throwable) {
      runCatching { if (target.exists()) target.delete() }
      throw t
    } finally {
      archive.close()
    }
    Log.d(
      TAG,
      "extract done: ${target.length()} bytes in ${System.currentTimeMillis() - t0}ms",
    )
    target
  }

  fun cacheFile(context: Context, parentUri: Uri, entryName: String): File {
    val parentHash = PositionKey.bookIdFromUri(parentUri.toString())
    val entryHash = PositionKey.bookIdFromUri(entryName)
    val dir = File(context.cacheDir, DIR)
    return File(dir, "${parentHash}__${entryHash}.$EXT")
  }

  suspend fun clearAll(context: Context): Int = withContext(Dispatchers.IO) {
    val dir = File(context.cacheDir, DIR)
    if (!dir.exists()) return@withContext 0
    var deleted = 0
    dir.listFiles()?.forEach { if (it.delete()) deleted++ }
    deleted
  }

  internal fun copyToLimited(
    input: InputStream,
    output: OutputStream,
    limitBytes: Long,
  ): Long {
    require(limitBytes >= 0) { "limitBytes must be >= 0" }
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var total = 0L
    while (true) {
      val read = input.read(buffer)
      if (read < 0) break
      total += read
      if (total > limitBytes) {
        throw IOException("nested archive too large: $total > $limitBytes")
      }
      output.write(buffer, 0, read)
    }
    return total
  }

  private const val MAX_NESTED_ARCHIVE_BYTES = 512L * 1024L * 1024L
}
