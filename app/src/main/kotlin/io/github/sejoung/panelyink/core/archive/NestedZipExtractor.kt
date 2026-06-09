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
    // 추출 캐시는 결정적 이름으로 누적되어 [clearAll](전체 리셋)로만 비워졌다 — ZIP-of-CBZ를
    // 여러 권 열면 무한히 커진다. 상한 초과 시 오래된 것부터(방금 추출한 target 제외) 정리.
    pruneToLimit(context, keep = target)
    target
  }

  /**
   * 추출 캐시를 [maxBytes] 이하로 LRU 정리. [keep]은 방금 추출해 곧 열릴 파일이라 보존한다.
   * (열려 있는 파일을 unlink해도 FD는 유효하지만, 다음 진입에서 불필요한 재추출을 막기 위해 제외.)
   */
  internal suspend fun pruneToLimit(
    context: Context,
    keep: File? = null,
    maxBytes: Long = MAX_CACHE_BYTES,
  ): Int = withContext(Dispatchers.IO) {
    val dir = File(context.cacheDir, DIR)
    val files = dir.listFiles()?.filter { it.isFile } ?: return@withContext 0
    var total = files.sumOf { it.length() }
    if (total <= maxBytes) return@withContext 0
    var deleted = 0
    // 오래된 것(lastModified 오름차순) 먼저 삭제, keep은 건너뜀.
    for (file in files.sortedBy { it.lastModified() }) {
      if (total <= maxBytes) break
      if (keep != null && file == keep) continue
      val size = file.length()
      if (file.delete()) {
        total -= size
        deleted++
      }
    }
    if (deleted > 0) Log.d(TAG, "prune: deleted=$deleted remaining=${total / 1024}KB")
    deleted
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

  /** 추출 캐시 디스크 상한. 단권 최대치(512MB) 2권 + 여유. 초과 시 [pruneToLimit]가 LRU 정리. */
  private const val MAX_CACHE_BYTES = 1024L * 1024L * 1024L
}
