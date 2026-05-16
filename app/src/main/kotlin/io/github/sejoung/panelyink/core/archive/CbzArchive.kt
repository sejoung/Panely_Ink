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
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.channels.SeekableByteChannel
import java.util.concurrent.LinkedBlockingDeque

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
 *
 * **병렬 read (v1.1):** Commons Compress `ZipFile`은 thread-safe가 아니다 — 단일
 * [SeekableByteChannel]을 모든 entry InputStream이 공유하므로 두 InputStream에서 동시 read 시
 * 채널 position이 손상된다. 두쪽 보기에서 두 페이지를 병렬로 디코드하려면 독립된 [ZipFile] 인스턴스가
 * 필요하다. [open]의 [PARALLEL_READERS] 매개변수만큼 [ParcelFileDescriptor.dup]으로 FD를 복제해
 * 각각의 [ZipFile]을 만들어 풀에 넣어둔다. [openPage]는 풀에서 [Reader]를 빌려 [InputStream]을 반환하고,
 * 그 [InputStream]이 close 되면 풀로 돌려보낸다.
 *
 * 표지 추출/라이브러리 스캔처럼 병렬이 필요 없는 경로는 `parallelReaders=1`로 기본값.
 * 본문 세션만 2를 요청해 추가 ZipFile 빌드 비용(중앙 디렉토리 1회 더 파싱, 수십~수백 KB)을 감수.
 */
class CbzArchive private constructor(
    private val readers: List<Reader>,
    val pages: List<CbzPage>,
    /**
     * ZIP-of-CBZ 시리즈 — 이 아카이브 안의 .cbz/.zip 항목들. PRD §6.1 "중첩 아카이브
     * 추출". [pages]가 비어있고 [nestedArchives]가 2+이면 가상 폴더(시리즈)로 취급.
     * 단일 권 + nested = 혼합 케이스는 1차에선 [pages] 우선(일반 reader).
     */
    val nestedArchives: List<NestedArchiveEntry>,
) : Closeable {

    private val readerPool: LinkedBlockingDeque<Reader> =
        LinkedBlockingDeque<Reader>(readers.size).apply { readers.forEach { put(it) } }

    @Volatile
    private var closed = false

    /** pages 비어있고 nested 2+ → ZIP-of-CBZ 시리즈로 취급. */
    val isSeriesArchive: Boolean
        get() = pages.isEmpty() && nestedArchives.size >= 2

    fun openPage(pageIndex: Int): InputStream {
        require(pageIndex in pages.indices) { "page $pageIndex out of bounds [${pages.size}]" }
        return openEntryByName(pages[pageIndex].name)
    }

    /** ZIP-of-CBZ에서 nested entry를 OutputStream 또는 file로 추출하기 위한 InputStream. */
    fun openNestedEntry(entryName: String): InputStream = openEntryByName(entryName)

    fun nestedEntrySize(entryName: String): Long {
        nestedArchives.firstOrNull { it.entryName == entryName }?.let { return it.size }
        pages.firstOrNull { it.name == entryName }?.let { return it.size }
        // Fallback: 캐시된 메타에 없으면 ZipFile에서 직접 조회 (희귀 케이스).
        val reader = borrowReader()
        try {
            return reader.zipFile.getEntry(entryName)?.size
                ?: throw IOException("entry not found: $entryName")
        } finally {
            returnReader(reader)
        }
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
        if (closed) throw IOException("archive closed")
        val reader = borrowReader()
        try {
            val nestedEntry = reader.zipFile.getEntry(entryName) ?: return null
            return reader.zipFile.getInputStream(nestedEntry).use { nestedStream ->
                ZipArchiveInputStream(nestedStream).use { zis ->
                    var bytes: ByteArray? = null
                    while (true) {
                        @Suppress("DEPRECATION")
                        val zipEntry: ZipArchiveEntry = zis.nextZipEntry ?: break
                        if (zipEntry.isDirectory) continue
                        if (!zipEntry.name.isImageEntry()) continue
                        val declaredSize = zipEntry.size
                        if (declaredSize > MAX_NESTED_COVER_IMAGE_BYTES) {
                            throw IOException(
                                "nested cover image too large: $declaredSize > $MAX_NESTED_COVER_IMAGE_BYTES",
                            )
                        }
                        bytes = readBytesLimited(zis, MAX_NESTED_COVER_IMAGE_BYTES)
                        break
                    }
                    bytes
                }
            }
        } finally {
            returnReader(reader)
        }
    }

    /**
     * 풀에서 [Reader] 1개 차용. 모두 사용 중이면 반환될 때까지 block.
     *
     * 호출은 항상 IO dispatcher 위에서 발생(decoder.decode가 `withContext(Dispatchers.IO)` 안에서 호출).
     * 1개 reader가 끝없이 점유되는 패턴은 없으므로 block은 짧고 안전.
     */
    private fun borrowReader(): Reader {
        if (closed) throw IOException("archive closed")
        val r = readerPool.take()
        if (closed) {
            readerPool.put(r)
            throw IOException("archive closed")
        }
        return r
    }

    private fun returnReader(reader: Reader) {
        readerPool.put(reader)
    }

    private fun openEntryByName(name: String): InputStream {
        val reader = borrowReader()
        return try {
            val entry = reader.zipFile.getEntry(name)
                ?: throw IOException("entry not found: $name")
            PooledInputStream(reader.zipFile.getInputStream(entry), reader, ::returnReader)
        } catch (t: Throwable) {
            returnReader(reader)
            throw t
        }
    }

    override fun close() {
        closed = true
        // in-flight 디코드가 점유한 reader도 포함해 모두 강제 close — 진행 중이던 read는
        // IOException 발생 후 호출자(ReaderViewModel preloadJob)가 이미 cancel 되어 있어 silent 처리.
        readers.forEach { r ->
            runCatching { r.zipFile.close() }
            runCatching { r.channel.close() }
            runCatching { r.pfd.close() }
        }
    }

    /**
     * 풀 항목 1개 — 독립된 PFD/Channel/ZipFile 3-tuple. 각 Reader가 자기 채널만 만지므로 thread-safe.
     */
    private class Reader(
        val zipFile: ZipFile,
        val channel: SeekableByteChannel,
        val pfd: ParcelFileDescriptor,
    )

    /**
     * 풀에서 빌린 [Reader]의 InputStream을 위임. close 시 Reader를 풀로 돌려보낸다.
     *
     * `use{}` 패턴과 호환 — `decoder.decode`가 `archive.openPage(...).use {}`로 stream을 닫으면
     * Reader가 자동 반환되어 다음 디코드가 이어서 사용할 수 있다.
     */
    private class PooledInputStream(
        private val delegate: InputStream,
        private val reader: Reader,
        private val release: (Reader) -> Unit,
    ) : InputStream() {
        private var released = false

        override fun read(): Int = delegate.read()
        override fun read(b: ByteArray): Int = delegate.read(b)
        override fun read(b: ByteArray, off: Int, len: Int): Int = delegate.read(b, off, len)
        override fun available(): Int = delegate.available()
        override fun skip(n: Long): Long = delegate.skip(n)
        override fun mark(readlimit: Int) = delegate.mark(readlimit)
        override fun reset() = delegate.reset()
        override fun markSupported(): Boolean = delegate.markSupported()

        override fun close() {
            try {
                delegate.close()
            } finally {
                if (!released) {
                    released = true
                    release(reader)
                }
            }
        }
    }

    companion object {
        private const val TAG = "PanelyInk.Archive"
        private const val MAX_NESTED_COVER_IMAGE_BYTES = 64L * 1024L * 1024L

        /** 본문 세션 두쪽 보기 병렬 디코드용 권장치. 표지/라이브러리 스캔은 1로 충분. */
        const val PARALLEL_READERS_FOR_SPREAD = 2

        suspend fun open(
            context: Context,
            uri: Uri,
            parallelReaders: Int = 1,
        ): CbzArchive = withContext(Dispatchers.IO) {
            require(parallelReaders >= 1) { "parallelReaders must be >= 1, got $parallelReaders" }
            val pfd = context.contentResolver.openFileDescriptor(uri, "r")
                ?: throw IOException("cannot open $uri")
            try {
                openInternal(pfd, parallelReaders)
            } catch (t: Throwable) {
                runCatching { pfd.close() }
                throw t
            }
        }

        /**
         * [java.io.File] 기반 — nested ZIP-of-CBZ에서 추출한 임시 cbz를 열 때 사용.
         * SAF 경로 없이 직접 디스크 파일.
         */
        suspend fun open(
            file: java.io.File,
            parallelReaders: Int = 1,
        ): CbzArchive = withContext(Dispatchers.IO) {
            require(parallelReaders >= 1) { "parallelReaders must be >= 1, got $parallelReaders" }
            val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            try {
                openInternal(pfd, parallelReaders)
            } catch (t: Throwable) {
                runCatching { pfd.close() }
                throw t
            }
        }

        private fun openInternal(primaryPfd: ParcelFileDescriptor, parallelReaders: Int): CbzArchive {
            val t0 = System.currentTimeMillis()
            val pfds = mutableListOf<ParcelFileDescriptor>(primaryPfd)
            val channels = mutableListOf<SeekableByteChannel>()
            val zipFiles = mutableListOf<ZipFile>()
            try {
                val firstChannel = FileInputStream(primaryPfd.fileDescriptor).channel
                channels += firstChannel
                Log.d(TAG, "channel size=${runCatching { firstChannel.size() }.getOrDefault(-1L)} bytes, statSize=${primaryPfd.statSize}")
                zipFiles += buildZipFile(firstChannel)

                // Primary 1개 + dup된 (parallelReaders - 1)개. 각 dup은 독립 FD라 read position이 분리됨.
                repeat(parallelReaders - 1) {
                    val dup = primaryPfd.dup()
                    pfds += dup
                    val ch = FileInputStream(dup.fileDescriptor).channel
                    channels += ch
                    zipFiles += buildZipFile(ch)
                }

                val (pages, nestedArchives) = enumerateEntries(zipFiles[0])
                val readers = zipFiles.mapIndexed { i, zf -> Reader(zf, channels[i], pfds[i]) }
                Log.d(
                    TAG,
                    "open total: ${pages.size} pages, ${nestedArchives.size} nested, " +
                        "readers=$parallelReaders in ${System.currentTimeMillis() - t0}ms",
                )
                return CbzArchive(readers, pages, nestedArchives)
            } catch (t: Throwable) {
                zipFiles.forEach { runCatching { it.close() } }
                channels.forEach { runCatching { it.close() } }
                pfds.forEach { runCatching { it.close() } }
                throw t
            }
        }

        /**
         * 1차: LFH 검증 스킵으로 빠르게. 표준 CBZ는 이걸로 충분. 실패 시 채널 position을 0으로 되돌리고
         * LFH 검증 켜고 재시도 (손상 zip 케이스).
         */
        private fun buildZipFile(channel: SeekableByteChannel): ZipFile {
            return try {
                ZipFile.builder()
                    .setSeekableByteChannel(channel)
                    .setIgnoreLocalFileHeader(true)
                    .get()
            } catch (firstAttempt: Throwable) {
                Log.w(TAG, "fast open failed, retrying with LFH validation", firstAttempt)
                runCatching { channel.position(0) }
                ZipFile.builder()
                    .setSeekableByteChannel(channel)
                    .setIgnoreLocalFileHeader(false)
                    .get()
            }
        }

        private fun enumerateEntries(zipFile: ZipFile): Pair<List<CbzPage>, List<NestedArchiveEntry>> {
            val pageList = mutableListOf<CbzPage>()
            val nestedList = mutableListOf<NestedArchiveEntry>()
            val entries = zipFile.entries
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (entry.isDirectory) continue
                when {
                    entry.name.isImageEntry() -> {
                        pageList += CbzPage(
                            name = entry.name,
                            size = entry.size.coerceAtLeast(0),
                        )
                    }
                    entry.name.isNestedArchiveEntry() -> {
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
            return sortedPages to sortedNested
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

        internal fun readBytesLimited(input: InputStream, limitBytes: Long): ByteArray {
            require(limitBytes >= 0) { "limitBytes must be >= 0" }
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var total = 0L
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                if (total > limitBytes) {
                    throw IOException("nested cover image too large: $total > $limitBytes")
                }
                output.write(buffer, 0, read)
            }
            return output.toByteArray()
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
