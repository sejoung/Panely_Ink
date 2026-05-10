package io.github.sejoung.panelyink.core.archive

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import io.github.sejoung.panelyink.core.sort.NaturalOrderComparator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream
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
    /**
     * ZIP-of-CBZ 시리즈 — 이 아카이브 안의 .cbz/.zip 항목들. PRD §6.1 "중첩 아카이브
     * 추출". [pages]가 비어있고 [nestedArchives]가 2+이면 가상 폴더(시리즈)로 취급.
     * 단일 권 + nested = 혼합 케이스는 1차에선 [pages] 우선(일반 reader).
     */
    val nestedArchives: List<NestedArchiveEntry>,
    private val entriesByName: Map<String, ZipArchiveEntry>,
) : Closeable {

    /** pages 비어있고 nested 2+ → ZIP-of-CBZ 시리즈로 취급. */
    val isSeriesArchive: Boolean
        get() = pages.isEmpty() && nestedArchives.size >= 2

    fun openPage(pageIndex: Int): InputStream {
        require(pageIndex in pages.indices) { "page $pageIndex out of bounds [${pages.size}]" }
        val name = pages[pageIndex].name
        val entry = entriesByName[name] ?: throw IOException("entry not found: $name")
        return zipFile.getInputStream(entry)
    }

    /** ZIP-of-CBZ에서 nested entry를 OutputStream 또는 file로 추출하기 위한 InputStream. */
    fun openNestedEntry(entryName: String): InputStream {
        val entry = entriesByName[entryName] ?: throw IOException("entry not found: $entryName")
        return zipFile.getInputStream(entry)
    }

    /**
     * ZIP-of-CBZ 시리즈의 가상 폴더 표지용 — 부모 ZIP에서 nested cbz를 디스크 추출 없이
     * 메모리에서 streaming으로 열고, 첫 image entry의 bytes를 반환. [NestedZipExtractor]
     * 디스크 캐시 없이도 라이브러리에서 즉시 표지 표시 가능.
     *
     * 비용: nested cbz의 첫 image entry까지 stream을 read. cbz 안 첫 entry가 image면
     * 빠르고(압축 안 된 cbz는 첫 KB), 마지막이면 stream 전체 read. 평균 200~800ms.
     *
     * 표지 추출 후 결과는 호출자가 [BitmapFactory.decodeByteArray]로 디코드 + sample.
     */
    fun firstImageBytesInNested(entryName: String): ByteArray? {
        val nestedEntry = entriesByName[entryName] ?: return null
        return zipFile.getInputStream(nestedEntry).use { nestedStream ->
            ZipArchiveInputStream(nestedStream).use { zis ->
                var bytes: ByteArray? = null
                while (true) {
                    @Suppress("DEPRECATION")
                    val zipEntry: ZipArchiveEntry = zis.nextZipEntry ?: break
                    if (zipEntry.isDirectory) continue
                    if (!zipEntry.name.isImageEntry()) continue
                    bytes = zis.readBytes()
                    break
                }
                bytes
            }
        }
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
            openInternal(pfd)
        }

        /**
         * [java.io.File] 기반 — nested ZIP-of-CBZ에서 추출한 임시 cbz를 열 때 사용.
         * SAF 경로 없이 직접 디스크 파일.
         */
        suspend fun open(file: java.io.File): CbzArchive = withContext(Dispatchers.IO) {
            val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            openInternal(pfd)
        }

        private fun openInternal(pfd: ParcelFileDescriptor): CbzArchive {
            val t0 = System.currentTimeMillis()
            val fis = FileInputStream(pfd.fileDescriptor)
            val channel = fis.channel
            Log.d(TAG, "channel size=${runCatching { channel.size() }.getOrDefault(-1L)} bytes, statSize=${pfd.statSize}")

            val tZf0 = System.currentTimeMillis()
            // 1차: LFH 검증 스킵으로 빠르게 — 표준 CBZ는 이걸로 충분.
            // 1차 실패 시 손상 zip 가능성. 채널을 처음으로 되돌리고 LFH 검증 켜고 재시도.
            val zipFile = try {
                ZipFile.builder()
                    .setSeekableByteChannel(channel)
                    .setIgnoreLocalFileHeader(true)
                    .get()
            } catch (firstAttempt: Throwable) {
                Log.w(TAG, "fast open failed, retrying with LFH validation", firstAttempt)
                runCatching { channel.position(0) }
                try {
                    ZipFile.builder()
                        .setSeekableByteChannel(channel)
                        .setIgnoreLocalFileHeader(false)
                        .get()
                } catch (secondAttempt: Throwable) {
                    runCatching { channel.close() }
                    runCatching { pfd.close() }
                    throw secondAttempt
                }
            }
            val tZf = System.currentTimeMillis()
            Log.d(TAG, "ZipFile build: ${tZf - tZf0}ms")

            val triple = try {
                val tEnum0 = System.currentTimeMillis()
                val byName = mutableMapOf<String, ZipArchiveEntry>()
                val pageList = mutableListOf<CbzPage>()
                val nestedList = mutableListOf<NestedArchiveEntry>()
                val entries = zipFile.entries
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    if (entry.isDirectory) continue
                    when {
                        entry.name.isImageEntry() -> {
                            byName[entry.name] = entry
                            pageList += CbzPage(
                                name = entry.name,
                                size = entry.size.coerceAtLeast(0),
                            )
                        }
                        entry.name.isNestedArchiveEntry() -> {
                            byName[entry.name] = entry
                            nestedList += NestedArchiveEntry(
                                entryName = entry.name,
                                displayName = entry.name.substringAfterLast('/'),
                                size = entry.size.coerceAtLeast(0),
                            )
                        }
                    }
                }
                val sortedPages = pageList.sortedWith(compareBy(NaturalOrderComparator) { it.name })
                val sortedNested = nestedList.sortedWith(
                    compareBy(NaturalOrderComparator) { it.displayName },
                )
                Log.d(
                    TAG,
                    "entries enum + sort: ${System.currentTimeMillis() - tEnum0}ms " +
                        "(${pageList.size} images, ${nestedList.size} nested)",
                )
                Triple(sortedPages, sortedNested, byName.toMap())
            } catch (t: Throwable) {
                runCatching { zipFile.close() }
                runCatching { channel.close() }
                runCatching { pfd.close() }
                throw t
            }
            val pages = triple.first
            val nestedArchives = triple.second
            val byName = triple.third

            Log.d(
                TAG,
                "open total: ${pages.size} pages, ${nestedArchives.size} nested " +
                    "in ${System.currentTimeMillis() - t0}ms",
            )
            return CbzArchive(zipFile, channel, pfd, pages, nestedArchives, byName)
        }

        private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "bmp", "gif")
        private val NESTED_ARCHIVE_EXTENSIONS = setOf("cbz", "zip")

        private fun String.isImageEntry(): Boolean {
            val name = substringAfterLast('/')
            if (name.isEmpty() || name.startsWith(".") || contains("__MACOSX/")) return false
            val ext = name.substringAfterLast('.', missingDelimiterValue = "").lowercase()
            return ext in IMAGE_EXTENSIONS
        }

        private fun String.isNestedArchiveEntry(): Boolean {
            val name = substringAfterLast('/')
            if (name.isEmpty() || name.startsWith(".") || contains("__MACOSX/")) return false
            val ext = name.substringAfterLast('.', missingDelimiterValue = "").lowercase()
            return ext in NESTED_ARCHIVE_EXTENSIONS
        }
    }
}

/** ZIP-of-CBZ에서 발견한 nested entry. */
data class NestedArchiveEntry(
    /** 부모 ZIP 안 entry 전체 경로 — extract 시 사용. */
    val entryName: String,
    /** 사용자에게 보여줄 이름(basename). */
    val displayName: String,
    /** 압축 해제 후 크기 — 추출 디스크 공간 추정. */
    val size: Long,
)
