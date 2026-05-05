package io.github.sejoung.panelyink.core.archive

import io.github.sejoung.panelyink.core.sort.NaturalOrderComparator
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * CBZ / ZIP 아카이브에서 만화 페이지 엔트리를 열거한다.
 *
 * 입력: 매번 새 InputStream을 만들어주는 팩토리 — SAF의 `ContentResolver
 * .openInputStream(uri)` 또는 `FileInputStream(file)`을 그대로 넘기면 된다.
 *
 * v1.0 정책 (PRD §6.1, §8):
 * - 헤더 전용 빠른 열거 → CentralDirectory 없이 ZipInputStream 1패스로 엔트리 메타만 수집
 * - 페이지 바이트 fetch는 매번 새 스트림을 열어 해당 엔트리까지 skip — O(N) per fetch.
 *   M1에서 RandomAccessFile/ZipFile 기반 소스로 교체 예정 (캐시 디렉터리에
 *   복사하거나 `/proc/self/fd/$fd` 트릭).
 * - 중첩 아카이브(zip in zip)는 PRD §6.1에 따라 최대 3단계까지 추출 — M1에서 처리.
 *
 * 이미지가 아닌 엔트리(주석 텍스트, 메타파일, ComicInfo.xml)는 페이지 목록에서 제외한다.
 */
class CbzLoader(private val streamFactory: () -> InputStream) {

    /**
     * 아카이브의 모든 이미지 엔트리를 자연 정렬해서 돌려준다. v1.0 진입점.
     */
    fun listPages(): List<CbzPage> {
        val raw = streamFactory().use { input ->
            val zis = ZipInputStream(input.buffered())
            val out = mutableListOf<CbzPage>()
            generateSequence { zis.nextEntry }.forEach { entry ->
                if (!entry.isDirectory && entry.name.isImageEntry()) {
                    out += CbzPage(
                        name = entry.name,
                        size = entry.size.takeIf { it >= 0 } ?: 0L,
                    )
                }
                zis.closeEntry()
            }
            out
        }
        return raw.sortedWith(compareBy(NaturalOrderComparator) { it.name })
    }

    /**
     * 정렬 후 [pageIndex]에 해당하는 엔트리의 바이트 스트림을 새로 연다.
     * 호출자가 close 책임을 가진다.
     */
    fun openPage(pages: List<CbzPage>, pageIndex: Int): InputStream {
        require(pageIndex in pages.indices) { "page index $pageIndex out of bounds [${pages.size}]" }
        val target = pages[pageIndex].name
        val input = streamFactory()
        val zis = ZipInputStream(input.buffered())
        while (true) {
            val entry = zis.nextEntry
                ?: error("entry $target not found in archive")
            if (!entry.isDirectory && entry.name == target) {
                return zis // 호출자가 close → underlying stream도 같이 닫힘
            }
            zis.closeEntry()
        }
    }

    companion object {
        private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "bmp", "gif")

        private fun String.isImageEntry(): Boolean {
            val name = substringAfterLast('/')
            // OS X 메타파일 / 숨김 디렉터리 결과물 제거
            if (name.isEmpty() || name.startsWith(".") || contains("__MACOSX/")) return false
            val ext = name.substringAfterLast('.', missingDelimiterValue = "").lowercase()
            return ext in IMAGE_EXTENSIONS
        }
    }
}

/** 아카이브 안 페이지 1장을 식별하는 메타데이터. 본문 디코드는 별도 모듈. */
data class CbzPage(
    val name: String,
    val size: Long,
)
