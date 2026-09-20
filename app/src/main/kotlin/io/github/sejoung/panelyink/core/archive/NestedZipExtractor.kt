package io.github.sejoung.panelyink.core.archive

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import io.github.sejoung.panelyink.core.position.PositionKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * ZIP-of-CBZ에서 nested entry를 단일 cbz 파일로 추출한다.
 *
 * Apache Commons Compress가 nested entry에 대해 random access를 지원하지 않아(streaming만),
 * 임시 파일로 1회 추출 후 그 파일을 일반 [CbzArchive]로 연다.
 *
 * **원자성:** 추출은 `<최종이름>.<uuid>.tmp`에 쓰고 끝난 뒤 최종 이름으로 rename 한다. 최종 경로에
 * 직접 쓰면 추출 도중(최대 512MB, 수십 초) 프로세스가 죽었을 때 잘린 cbz가 남아 영원히 cache hit로
 * 취급되고, 동시에 도는 라이브러리 표지 추출도 쓰다 만 파일을 보게 된다. `.cbz`로 끝나는 파일은
 * 항상 완성본.
 *
 * **캐시 키:** `hash(parentUri)__hash(entryName)[__부모버전].cbz`. 부모버전은 부모 ZIP의
 * 크기+수정시각 — 같은 Uri에 다른 ZIP을 덮어써도 옛 추출본을 계속 내주지 않게.
 */
object NestedZipExtractor {

  private const val DIR = "nested"
  private const val EXT = "cbz"
  private const val TEMP_SUFFIX = ".tmp"
  private const val TAG = "PanelyInk.NestedExtractor"

  /**
   * 추출 직렬화 — 같은 target을 두 호출이 동시에 쓰지 않게. 추출은 reader 진입에서만 일어나고
   * 디스크 IO 바운드라 target별 lock 대신 전역 1개로 충분.
   */
  private val extractMutex = Mutex()

  /** 지금 쓰고 있는 temp 파일들 — [pruneToLimit]/[clearAll]의 stale temp 청소에서 제외. */
  private val inFlightTemps: MutableSet<File> = ConcurrentHashMap.newKeySet<File>()

  suspend fun extract(
    context: Context,
    parentUri: Uri,
    entryName: String,
  ): File = withContext(Dispatchers.IO) {
    val target = versionedCacheFile(context, parentUri, entryName, parentVersion(context, parentUri))
    if (touchIfCached(target)) {
      Log.d(TAG, "cache hit: ${target.name}")
      return@withContext target
    }

    val t0 = System.currentTimeMillis()
    extractMutex.withLock {
      // lock을 기다리는 동안 다른 호출이 같은 파일을 만들어 놨을 수 있다.
      if (touchIfCached(target)) {
        Log.d(TAG, "cache hit after wait: ${target.name}")
        return@withContext target
      }
      Log.d(TAG, "extract start: parent=$parentUri entry=$entryName")
      val archive = CbzArchive.open(context, parentUri)
      try {
        val declaredSize = archive.nestedEntrySize(entryName)
        if (declaredSize > MAX_NESTED_ARCHIVE_BYTES) {
          throw IOException(
            "nested archive too large: $declaredSize > $MAX_NESTED_ARCHIVE_BYTES",
          )
        }
        archive.openNestedEntry(entryName).use { input ->
          writeAtomically(target) { output ->
            // blocking copy라 스스로 취소를 확인해야 한다 — 사용자가 뒤로 나가면 512MB를 끝까지 쓰지 않게.
            copyToLimited(input, output, MAX_NESTED_ARCHIVE_BYTES) { ensureActive() }
          }
        }
      } finally {
        archive.close()
      }
      // 부모 ZIP이 바뀌기 전 버전의 추출본은 이제 쓸모없다.
      deleteSiblings(target, cachePrefix(parentUri, entryName))
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
   * [extract]가 만든 추출본을 버린다(모든 부모버전). 캐시된 cbz가 손상되어 열리지 않을 때
   * 호출자가 이걸로 지우고 [extract]를 한 번 더 부르면 새로 추출된다.
   *
   * @return 삭제한 파일 수
   */
  suspend fun invalidate(
    context: Context,
    parentUri: Uri,
    entryName: String,
  ): Int = withContext(Dispatchers.IO) {
    val deleted = deleteSiblings(
      keep = null,
      prefix = cachePrefix(parentUri, entryName),
      dir = File(context.cacheDir, DIR),
    )
    Log.d(TAG, "invalidate: entry=$entryName deleted=$deleted")
    deleted
  }

  /**
   * 추출 캐시를 [maxBytes] 이하로 LRU 정리. [keep]은 방금 추출해 곧 열릴 파일이라 보존한다.
   * (열려 있는 파일을 unlink해도 FD는 유효하지만, 다음 진입에서 불필요한 재추출을 막기 위해 제외.)
   * 프로세스가 죽으며 남긴 stale temp 파일도 여기서 치운다.
   */
  internal suspend fun pruneToLimit(
    context: Context,
    keep: File? = null,
    maxBytes: Long = MAX_CACHE_BYTES,
  ): Int = withContext(Dispatchers.IO) {
    val deleted = pruneDir(File(context.cacheDir, DIR), keep, maxBytes)
    if (deleted > 0) Log.d(TAG, "prune: deleted=$deleted")
    deleted
  }

  internal fun pruneDir(dir: File, keep: File?, maxBytes: Long): Int {
    var deleted = 0
    val files = dir.listFiles()?.filter { it.isFile && it !in inFlightTemps } ?: return 0
    // temp인데 쓰는 중이 아니면 죽은 추출의 잔해 — 상한과 무관하게 삭제.
    val (staleTemps, cached) = files.partition { it.name.endsWith(TEMP_SUFFIX) }
    staleTemps.forEach { if (it.delete()) deleted++ }
    var total = cached.sumOf { it.length() }
    // 오래된 것(lastModified 오름차순) 먼저 삭제, keep은 건너뜀. cache hit 때마다
    // [touchIfCached]가 lastModified를 갱신하므로 "최근에 읽은 순"이 된다.
    for (file in cached.sortedBy { it.lastModified() }) {
      if (total <= maxBytes) break
      if (keep != null && file == keep) continue
      val size = file.length()
      if (file.delete()) {
        total -= size
        deleted++
      }
    }
    return deleted
  }

  /**
   * (부모, entry)의 추출본 경로 — 표지 추출처럼 "이미 추출돼 있으면 쓰겠다"는 호출자용.
   * 부모버전이 붙은 파일이 있으면 그중 가장 최근 것을, 없으면 버전 없는 기본 경로(존재하지 않을 수 있음)를
   * 돌려준다. 돌려준 파일이 존재하면 항상 완성본이다(쓰는 중인 파일은 `.tmp`).
   */
  fun cacheFile(context: Context, parentUri: Uri, entryName: String): File {
    val dir = File(context.cacheDir, DIR)
    val prefix = cachePrefix(parentUri, entryName)
    return siblings(dir, prefix).maxByOrNull { it.lastModified() }
      ?: File(dir, "$prefix.$EXT")
  }

  private fun versionedCacheFile(
    context: Context,
    parentUri: Uri,
    entryName: String,
    parentVersion: String?,
  ): File {
    val dir = File(context.cacheDir, DIR)
    val prefix = cachePrefix(parentUri, entryName)
    return File(dir, if (parentVersion != null) "${prefix}__$parentVersion.$EXT" else "$prefix.$EXT")
  }

  private fun cachePrefix(parentUri: Uri, entryName: String): String {
    val parentHash = PositionKey.bookIdFromUri(parentUri.toString())
    val entryHash = PositionKey.bookIdFromUri(entryName)
    return "${parentHash}__$entryHash"
  }

  /**
   * 부모 ZIP의 "크기_수정시각" — 캐시 키의 버전 성분. SAF document Uri가 아니거나 provider가
   * 값을 안 주면 null(버전 없는 키로 동작).
   */
  private fun parentVersion(context: Context, parentUri: Uri): String? = runCatching {
    context.contentResolver.query(
      parentUri,
      arrayOf(DocumentsContract.Document.COLUMN_SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED),
      null,
      null,
      null,
    )?.use { cursor ->
      if (!cursor.moveToFirst()) return@use null
      val sizeIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
      val modifiedIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
      val size = if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) cursor.getLong(sizeIdx) else -1L
      val modified = if (modifiedIdx >= 0 && !cursor.isNull(modifiedIdx)) cursor.getLong(modifiedIdx) else -1L
      if (size < 0 && modified <= 0) null else "${size}_$modified"
    }
  }.getOrNull()

  /** 같은 (부모, entry)의 완성된 추출본들 — 부모버전만 다른 파일. temp는 포함하지 않는다. */
  private fun siblings(dir: File, prefix: String): List<File> =
    dir.listFiles()?.filter {
      it.isFile && it.name.endsWith(".$EXT") &&
        (it.name == "$prefix.$EXT" || it.name.startsWith("${prefix}__"))
    } ?: emptyList()

  private fun deleteSiblings(
    keep: File?,
    prefix: String,
    dir: File? = keep?.parentFile,
  ): Int {
    if (dir == null) return 0
    var deleted = 0
    siblings(dir, prefix).forEach { if (it != keep && it.delete()) deleted++ }
    return deleted
  }

  /**
   * cache hit 판정 + LRU touch. hit면 lastModified를 지금으로 갱신해 [pruneDir]가 매일 읽는 권을
   * 먼저 지우지 않게 한다. (갱신하지 않으면 추출 시각 기준 FIFO가 된다.)
   */
  internal fun touchIfCached(target: File): Boolean {
    if (!target.exists() || target.length() <= 0) return false
    target.setLastModified(System.currentTimeMillis())
    return true
  }

  /**
   * [write]의 출력을 temp 파일에 받은 뒤 [target]으로 원자적 rename. 실패/취소 시 temp를 지우고
   * [target]은 건드리지 않는다.
   */
  internal fun writeAtomically(target: File, write: (OutputStream) -> Unit) {
    val dir = target.parentFile ?: throw IOException("no parent dir: $target")
    dir.mkdirs()
    val temp = File(dir, "${target.name}.${UUID.randomUUID()}$TEMP_SUFFIX")
    inFlightTemps += temp
    try {
      FileOutputStream(temp).use { output ->
        write(output)
        // rename 전에 데이터를 디스크에 내려야 전원이 나가도 "이름은 완성본인데 내용은 빈" 파일이 안 생긴다.
        output.fd.sync()
      }
      if (!temp.renameTo(target)) throw IOException("rename failed: ${temp.name} -> ${target.name}")
    } catch (t: Throwable) {
      runCatching { temp.delete() }
      throw t
    } finally {
      inFlightTemps -= temp
    }
  }

  suspend fun clearAll(context: Context): Int = withContext(Dispatchers.IO) {
    val dir = File(context.cacheDir, DIR)
    if (!dir.exists()) return@withContext 0
    var deleted = 0
    // 쓰는 중인 temp를 지우면 진행 중인 추출의 rename이 실패한다 — 그것만 남긴다.
    dir.listFiles()?.forEach { if (it !in inFlightTemps && it.delete()) deleted++ }
    deleted
  }

  internal fun copyToLimited(
    input: InputStream,
    output: OutputStream,
    limitBytes: Long,
    /** 버퍼 1개마다 호출 — 취소됐으면 여기서 throw 해 복사를 끊는다. */
    checkCancelled: () -> Unit = {},
  ): Long {
    require(limitBytes >= 0) { "limitBytes must be >= 0" }
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var total = 0L
    while (true) {
      checkCancelled()
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
