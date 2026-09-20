package io.github.sejoung.panelyink.core.archive

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import io.github.sejoung.panelyink.core.sort.NaturalOrderComparator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.zip.UnicodePathExtraField
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream
import org.apache.commons.compress.archivers.zip.ZipFile
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.Collections
import java.util.concurrent.LinkedBlockingDeque
import java.util.zip.CRC32

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
 * 필요하다. [open]의 `parallelReaders` 매개변수만큼 Uri/File을 **다시 열어** 각각의 [ZipFile]을 만들어
 * 풀에 넣어둔다. ([ParcelFileDescriptor.dup]은 쓰면 안 된다 — dup된 FD는 같은 open file description을
 * 가리켜 file offset을 공유하므로, `setIgnoreLocalFileHeader(true)`에서 entry 첫 open 시 일어나는
 * position()+read가 reader끼리 섞여 잘못된 dataOffset이 영구 저장된다.)
 * [openPage]는 풀에서 [Reader]를 빌려 [InputStream]을 반환하고, 그 [InputStream]이 close 되면 풀로 돌려보낸다.
 *
 * **entry 식별:** 페이지는 이름이 아니라 central directory 순번([CbzPage.entryIndex])으로 찾는다.
 * UTF-8 플래그 없는 zip(CP949 등)이나 중복 이름 zip에서 `getEntry(name)`은 첫 매치만 돌려줘
 * 서로 다른 페이지가 같은 이미지로 보이기 때문. 모든 reader는 같은 central directory를 파싱하므로 순번이 같다.
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
    /**
     * entry 이름 → central directory 순번. 이름으로 들어오는 조회([openNestedEntry] 등) 전용.
     * 중복 이름은 첫 entry 우선. 디코드 보정된 이름([decodeEntryName])과 Commons Compress 원래 이름
     * 둘 다 등록 — 이전 버전이 저장해 둔 nested entry 이름(깨진 이름)으로도 계속 열리게.
     */
    private val entryIndexByName: Map<String, Int>,
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
        val page = pages[pageIndex]
        // 이름이 아니라 순번으로 — 중복/깨진 이름에서도 정확히 그 entry를 연다.
        return if (page.entryIndex >= 0) openEntryByIndex(page.entryIndex) else openEntryByName(page.name)
    }

    /** ZIP-of-CBZ에서 nested entry를 OutputStream 또는 file로 추출하기 위한 InputStream. */
    fun openNestedEntry(entryName: String): InputStream = openEntryByName(entryName)

    fun nestedEntrySize(entryName: String): Long {
        nestedArchives.firstOrNull { it.entryName == entryName }?.let { return it.size }
        pages.firstOrNull { it.name == entryName }?.let { return it.size }
        // Fallback: 캐시된 메타에 없으면 entry 목록에서 직접 조회 (희귀 케이스).
        // entry 메타는 open 이후 불변이라 reader를 빌리지 않고 읽어도 안전.
        val index = entryIndexByName[entryName] ?: throw IOException("entry not found: $entryName")
        return readers[0].entries.getOrNull(index)?.size
            ?: throw IOException("entry not found: $entryName")
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
            val nestedEntry = entryIndexByName[entryName]?.let { reader.entries.getOrNull(it) }
                ?: return null
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
        val index = entryIndexByName[name] ?: throw IOException("entry not found: $name")
        return openEntryByIndex(index)
    }

    private fun openEntryByIndex(entryIndex: Int): InputStream {
        val reader = borrowReader()
        return try {
            val entry = reader.entries.getOrNull(entryIndex)
                ?: throw IOException("entry not found: #$entryIndex")
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
     * 풀 항목 1개 — 독립적으로 연 PFD/Channel/ZipFile 3-tuple. 각 Reader가 자기 채널(자기 file offset)만
     * 만지므로 thread-safe. [entries]는 central directory 순서 그대로 — [CbzPage.entryIndex]의 기준.
     */
    private class Reader(
        val zipFile: ZipFile,
        val entries: List<ZipArchiveEntry>,
        val channel: SeekableByteChannel,
        /** 채널의 원본 핸들 — 실제로는 [ParcelFileDescriptor]. (JVM 테스트에서는 채널 자신.) */
        val pfd: Closeable,
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
            openInternal(parallelReaders) {
                context.contentResolver.openFileDescriptor(uri, "r")
                    ?: throw IOException("cannot open $uri")
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
            openInternal(parallelReaders) {
                ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            }
        }

        /**
         * JVM 단위 테스트용 — android PFD/Log 없이 채널에서 직접 연다. 채널 1개 = reader 1개.
         * 채널 소유권은 반환된 [CbzArchive]로 넘어간다([close]에서 닫힘).
         */
        internal fun openChannels(channels: List<SeekableByteChannel>): CbzArchive {
            require(channels.isNotEmpty()) { "channels must not be empty" }
            val readers = mutableListOf<Reader>()
            try {
                channels.forEach { readers += newReader(it, it) }
                return assemble(readers)
            } catch (t: Throwable) {
                readers.forEach { runCatching { it.zipFile.close() } }
                channels.forEach { runCatching { it.close() } }
                throw t
            }
        }

        /**
         * [openPfd]를 reader 수만큼 호출해 **서로 독립된** FD를 연다. `dup()`은 file offset을 공유하므로
         * 쓰지 않는다(클래스 KDoc 참고).
         */
        private fun openInternal(
            parallelReaders: Int,
            openPfd: () -> ParcelFileDescriptor,
        ): CbzArchive {
            val t0 = System.currentTimeMillis()
            val readers = mutableListOf<Reader>()
            try {
                val primary = openReader(openPfd, logSize = true)
                readers += primary

                // 추가 reader는 병렬 디코드 최적화일 뿐 — 다시 열기에 실패하거나(provider가 거부 등)
                // 그 사이 파일이 바뀌어 central directory가 달라졌으면 버리고 있는 reader만으로 진행.
                repeat(parallelReaders - 1) {
                    val extra = try {
                        openReader(openPfd, logSize = false)
                    } catch (e: Exception) {
                        Log.w(TAG, "extra reader open failed, continuing with ${readers.size}", e)
                        null
                    }
                    if (extra != null) {
                        if (extra.entries.size == primary.entries.size) {
                            readers += extra
                        } else {
                            Log.w(
                                TAG,
                                "extra reader entry count mismatch " +
                                    "(${extra.entries.size} != ${primary.entries.size}), dropped",
                            )
                            closeReader(extra)
                        }
                    }
                }

                val archive = assemble(readers)
                Log.d(
                    TAG,
                    "open total: ${archive.pages.size} pages, ${archive.nestedArchives.size} nested, " +
                        "readers=${readers.size}/$parallelReaders in ${System.currentTimeMillis() - t0}ms",
                )
                return archive
            } catch (t: Throwable) {
                readers.forEach { closeReader(it) }
                throw t
            }
        }

        private fun openReader(openPfd: () -> ParcelFileDescriptor, logSize: Boolean): Reader {
            val pfd = openPfd()
            try {
                val channel = FileInputStream(pfd.fileDescriptor).channel
                if (logSize) {
                    Log.d(TAG, "channel size=${runCatching { channel.size() }.getOrDefault(-1L)} bytes, statSize=${pfd.statSize}")
                }
                return newReader(channel, pfd)
            } catch (t: Throwable) {
                runCatching { pfd.close() }
                throw t
            }
        }

        private fun newReader(channel: SeekableByteChannel, handle: Closeable): Reader {
            val zipFile = buildZipFile(channel)
            return Reader(zipFile, Collections.list(zipFile.entries), channel, handle)
        }

        private fun closeReader(reader: Reader) {
            runCatching { reader.zipFile.close() }
            runCatching { reader.channel.close() }
            runCatching { reader.pfd.close() }
        }

        private fun assemble(readers: List<Reader>): CbzArchive {
            val enumerated = enumerateEntries(readers[0].entries)
            return CbzArchive(
                readers.toList(),
                enumerated.pages,
                enumerated.nestedArchives,
                enumerated.entryIndexByName,
            )
        }

        /**
         * LFH 검증 스킵으로 빠르게 연다(central directory만 파싱). 표준 CBZ는 이걸로 충분.
         *
         * 실패 시 재시도하지 않는다 — 채널을 넘겨준 경우 Builder가 생성자 실패 시 그 채널을 닫아버려
         * 같은 채널로의 재시도는 항상 [java.nio.channels.ClosedChannelException]이 되고, 사용자에게
         * 보여줄 진짜 원인만 가린다. (LFH 검증을 켠다고 더 관대해지지도 않는다 — central directory
         * 파싱은 동일.) 원래 예외를 그대로 전파.
         */
        private fun buildZipFile(channel: SeekableByteChannel): ZipFile =
            ZipFile.builder()
                .setSeekableByteChannel(channel)
                .setIgnoreLocalFileHeader(true)
                .get()

        private class Enumerated(
            val pages: List<CbzPage>,
            val nestedArchives: List<NestedArchiveEntry>,
            val entryIndexByName: Map<String, Int>,
        )

        private fun enumerateEntries(entries: List<ZipArchiveEntry>): Enumerated {
            val pageList = mutableListOf<CbzPage>()
            val nestedList = mutableListOf<NestedArchiveEntry>()
            val indexByName = HashMap<String, Int>(entries.size * 2)
            val legacyNames = ArrayList<Pair<String, Int>>()
            entries.forEachIndexed { index, entry ->
                val name = decodeEntryName(entry)
                indexByName.putIfAbsent(name, index)
                if (name != entry.name) legacyNames += entry.name to index
                if (entry.isDirectory) return@forEachIndexed
                when {
                    name.isImageEntry() -> {
                        pageList += CbzPage(
                            name = name,
                            size = entry.size.coerceAtLeast(0),
                            entryIndex = index,
                        )
                    }
                    name.isNestedArchiveEntry() -> {
                        nestedList += NestedArchiveEntry(
                            // entryName은 bookId(`uri#entryName`)의 일부 — 진행 위치/북마크/표지 키가 여기에 걸려 있다.
                            // 보정된 이름으로 바꾸면 구형 인코딩 ZIP의 기존 기록이 전부 고아가 되므로 식별자는
                            // Commons Compress 원래 이름을 유지하고, 보정된 이름은 표시/정렬에만 쓴다.
                            entryName = entry.name,
                            displayName = name.substringAfterLast('/'),
                            size = entry.size.coerceAtLeast(0),
                        )
                    }
                }
            }
            // 이전 버전이 저장한 깨진 이름도 계속 조회되게 — 보정된 이름을 가리지 않도록 나중에 등록.
            legacyNames.forEach { (legacy, index) -> indexByName.putIfAbsent(legacy, index) }
            val sortedPages = pageList.sortedWith(compareBy(NaturalOrderComparator) { it.name })
            val sortedNested = nestedList.sortedWith(
                compareBy(NaturalOrderComparator) { it.displayName },
            )
            return Enumerated(sortedPages, sortedNested, indexByName)
        }

        /**
         * entry 이름 디코드 보정. UTF-8 플래그(EFS)가 없는 entry를 Commons Compress는 UTF-8로
         * (깨진 바이트는 `?`로 치환) 디코드해, CP949 zip의 `상/001.jpg`·`하/001.jpg`가 둘 다
         * `??/001.jpg`가 되고 자연 정렬도 무너진다. raw 바이트로 다시 디코드한다:
         * Info-ZIP Unicode Path extra → strict UTF-8 → legacy charset(CP949 → Shift_JIS) 순.
         * 전부 실패하면 Commons Compress 이름 그대로.
         */
        internal fun decodeEntryName(entry: ZipArchiveEntry): String {
            if (entry.generalPurposeBit.usesUTF8ForNames()) return entry.name
            val raw = entry.rawName ?: return entry.name
            // `setIgnoreLocalFileHeader(true)`에서는 Commons Compress가 Unicode extra를 적용하지 않는다.
            (entry.getExtraField(UnicodePathExtraField.UPATH_ID) as? UnicodePathExtraField)?.let { extra ->
                val unicodeName = extra.unicodeName
                if (unicodeName != null && extra.nameCRC32 == CRC32().apply { update(raw) }.value) {
                    return String(unicodeName, Charsets.UTF_8)
                }
            }
            decodeStrict(raw, Charsets.UTF_8)?.let { return it }
            for (charset in LEGACY_NAME_CHARSETS) {
                decodeStrict(raw, charset)?.let { return it }
            }
            return entry.name
        }

        private fun decodeStrict(bytes: ByteArray, charset: Charset): String? = try {
            charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (e: CharacterCodingException) {
            null
        }

        /**
         * UTF-8이 아닌 이름의 fallback 후보 — 한국어(CP949) 우선, 다음 일본어(Shift_JIS).
         * 기기(ICU)에 따라 charset 별칭이 달라 후보 중 지원되는 첫 번째를 쓴다.
         */
        private val LEGACY_NAME_CHARSETS: List<Charset> by lazy {
            listOf(
                listOf("x-windows-949", "MS949", "windows-949", "EUC-KR"),
                listOf("windows-31j", "Shift_JIS"),
            ).mapNotNull { aliases ->
                aliases.firstNotNullOfOrNull { runCatching { Charset.forName(it) }.getOrNull() }
            }
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
