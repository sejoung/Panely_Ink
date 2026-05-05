package io.github.sejoung.panelyink.core.archive

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import io.github.sejoung.panelyink.core.sort.NaturalOrderComparator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipFile
import java.io.Closeable
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.channels.SeekableByteChannel

/**
 * CBZ/ZIP 아카이브를 random-access로 연다.
 *
 * 구조: SAF Uri → [ParcelFileDescriptor] → [FileInputStream.channel] (SeekableByteChannel)
 *      → Apache Commons Compress [ZipFile]
 *
 * 캐시 복사 없이 SAF 스트림 위에 직접 random-access를 얹는다. Central Directory만
 * 끝에서 짧게 읽고, 페이지 fetch도 entry offset으로 직접 점프 — 큰 CBZ에서 첫 진입
 * 시간을 수십 초에서 100ms 미만으로 단축.
 *
 * 이전 시도와 차이:
 * - `/proc/self/fd/N` + java.util.zip.ZipFile : Android 11 SELinux 정책으로 차단
 * - 캐시 디렉토리 통째 복사 + java.util.zip.ZipFile : 220MB 책 첫 진입 ~11초 (외장 sdcard read 한계)
 *
 * Commons Compress의 `ZipFile`은 `SeekableByteChannel` 기반 random-access를 정식 지원.
 */
class CbzArchive private constructor(
    private val zipFile: ZipFile,
    private val channel: SeekableByteChannel,
    private val pfd: ParcelFileDescriptor,
    val pages: List<CbzPage>,
    private val entriesByName: Map<String, ZipArchiveEntry>,
) : Closeable {

    fun openPage(pageIndex: Int): InputStream {
        require(pageIndex in pages.indices) { "page $pageIndex out of bounds [${pages.size}]" }
        val name = pages[pageIndex].name
        val entry = entriesByName[name] ?: throw IOException("entry not found: $name")
        return zipFile.getInputStream(entry)
    }

    override fun close() {
        runCatching { zipFile.close() }
        runCatching { channel.close() }
        runCatching { pfd.close() }
    }

    companion object {
        private const val TAG = "PanelyInk.Archive"

        suspend fun open(context: Context, uri: Uri): CbzArchive = withContext(Dispatchers.IO) {
            val pfd = context.contentResolver.openFileDescriptor(uri, "r")
                ?: throw IOException("cannot open $uri")
            val t0 = System.currentTimeMillis()
            val fis = FileInputStream(pfd.fileDescriptor)
            val channel = fis.channel
            Log.d(TAG, "channel size=${runCatching { channel.size() }.getOrDefault(-1L)} bytes, statSize=${pfd.statSize}")

            val tZf0 = System.currentTimeMillis()
            val zipFile = try {
                ZipFile.builder()
                    .setSeekableByteChannel(channel)
                    // entry마다 LocalFileHeader 검증을 스킵 — N개 entry × random seek 누적 회피.
                    // 표준 CBZ에서는 안전. 비표준 zip(LFH/CD 불일치)에서만 문제 가능.
                    .setIgnoreLocalFileHeader(true)
                    .get()
            } catch (t: Throwable) {
                runCatching { channel.close() }
                runCatching { pfd.close() }
                throw t
            }
            val tZf = System.currentTimeMillis()
            Log.d(TAG, "ZipFile build: ${tZf - tZf0}ms")

            val (pages, byName) = try {
                val tEnum0 = System.currentTimeMillis()
                val byName = mutableMapOf<String, ZipArchiveEntry>()
                val pageList = mutableListOf<CbzPage>()
                val entries = zipFile.entries
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    if (!entry.isDirectory && entry.name.isImageEntry()) {
                        byName[entry.name] = entry
                        pageList += CbzPage(name = entry.name, size = entry.size.coerceAtLeast(0))
                    }
                }
                val sorted = pageList.sortedWith(compareBy(NaturalOrderComparator) { it.name })
                Log.d(TAG, "entries enum + sort: ${System.currentTimeMillis() - tEnum0}ms (${pageList.size} images)")
                sorted to byName.toMap()
            } catch (t: Throwable) {
                runCatching { zipFile.close() }
                runCatching { channel.close() }
                runCatching { pfd.close() }
                throw t
            }

            Log.d(TAG, "open total: ${pages.size} pages in ${System.currentTimeMillis() - t0}ms")
            CbzArchive(zipFile, channel, pfd, pages, byName)
        }

        private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "bmp", "gif")

        private fun String.isImageEntry(): Boolean {
            val name = substringAfterLast('/')
            if (name.isEmpty() || name.startsWith(".") || contains("__MACOSX/")) return false
            val ext = name.substringAfterLast('.', missingDelimiterValue = "").lowercase()
            return ext in IMAGE_EXTENSIONS
        }
    }
}
