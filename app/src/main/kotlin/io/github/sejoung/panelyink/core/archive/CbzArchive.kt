package io.github.sejoung.panelyink.core.archive

import android.content.Context
import android.net.Uri
import android.util.Log
import io.github.sejoung.panelyink.core.sort.NaturalOrderComparator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.ZipFile

/**
 * CBZ/ZIP 아카이브를 random-access로 연다.
 *
 * 사용 전략 — **앱 캐시 디렉토리에 한 번 복사 후 일반 File 경로로 [ZipFile]을 연다.**
 *
 * 이전 시도: `ContentResolver.openFileDescriptor` → `/proc/self/fd/N` 트릭은
 * Android 11 SELinux 정책이 SAF 노출 fd에 대해 막는 디바이스(Meebook M7 포함)가
 * 많아 `Permission denied`로 실패한다. 캐시 복사 방식은:
 *
 * - 첫 진입: SAF stream → 캐시 파일 복사 (100MB 기준 ~1~2초)
 * - 재진입: 캐시 hit → 즉시 (Central Directory 직참조)
 * - 페이지 fetch: 항상 random-access — 처음부터 skip 불필요
 * - SAF 권한 만료 후에도 캐시된 책은 열림
 *
 * 캐시 정리는 Android가 디스크 공간 압박 시 자동 — 별도 LRU 관리는 v1.5.
 */
class CbzArchive private constructor(
    private val zipFile: ZipFile,
    val pages: List<CbzPage>,
) : Closeable {

    fun openPage(pageIndex: Int): InputStream {
        require(pageIndex in pages.indices) { "page $pageIndex out of bounds [${pages.size}]" }
        val name = pages[pageIndex].name
        val entry = zipFile.getEntry(name) ?: throw IOException("entry not found: $name")
        return zipFile.getInputStream(entry)
    }

    override fun close() {
        runCatching { zipFile.close() }
    }

    companion object {
        private const val TAG = "PanelyInk.Archive"
        private const val COPY_BUFFER = 64 * 1024

        suspend fun open(context: Context, uri: Uri): CbzArchive = withContext(Dispatchers.IO) {
            val ctx = context.applicationContext
            val cacheFile = ensureCached(ctx, uri)
            val t0 = System.currentTimeMillis()
            val zipFile = ZipFile(cacheFile)
            val pages = try {
                zipFile.entries().asSequence()
                    .filter { !it.isDirectory && it.name.isImageEntry() }
                    .map { CbzPage(name = it.name, size = it.size.coerceAtLeast(0)) }
                    .sortedWith(compareBy(NaturalOrderComparator) { it.name })
                    .toList()
            } catch (t: Throwable) {
                runCatching { zipFile.close() }
                throw t
            }
            Log.d(TAG, "open: ${pages.size} entries in ${System.currentTimeMillis() - t0}ms (cache=${cacheFile.length() / 1024}KB)")
            CbzArchive(zipFile, pages)
        }

        private fun ensureCached(context: Context, uri: Uri): File {
            val key = sha1Hex(uri.toString())
            val dir = File(context.cacheDir, "archives").apply { mkdirs() }
            val target = File(dir, "$key.cbz")
            if (target.exists() && target.length() > 0) {
                Log.d(TAG, "cache hit $key (${target.length() / 1024}KB)")
                return target
            }
            val t0 = System.currentTimeMillis()
            val tmp = File(dir, "$key.cbz.tmp")
            try {
                val input = context.contentResolver.openInputStream(uri)
                    ?: throw IOException("cannot open $uri")
                input.use { src ->
                    FileOutputStream(tmp).use { dst ->
                        src.copyTo(dst, bufferSize = COPY_BUFFER)
                    }
                }
                if (!tmp.renameTo(target)) {
                    throw IOException("cannot rename cache file ${tmp.name} → ${target.name}")
                }
                Log.d(TAG, "cache miss → copied $key in ${System.currentTimeMillis() - t0}ms (${target.length() / 1024}KB)")
                return target
            } catch (t: Throwable) {
                runCatching { tmp.delete() }
                throw t
            }
        }

        private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "bmp", "gif")

        private fun String.isImageEntry(): Boolean {
            val name = substringAfterLast('/')
            if (name.isEmpty() || name.startsWith(".") || contains("__MACOSX/")) return false
            val ext = name.substringAfterLast('.', missingDelimiterValue = "").lowercase()
            return ext in IMAGE_EXTENSIONS
        }

        private fun sha1Hex(s: String): String {
            val bytes = MessageDigest.getInstance("SHA-1").digest(s.toByteArray(Charsets.UTF_8))
            return bytes.joinToString(separator = "") { "%02x".format(it) }
        }
    }
}
