package io.github.sejoung.panelyink.reader

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import io.github.sejoung.panelyink.core.archive.CbzLoader
import io.github.sejoung.panelyink.core.archive.CbzPage
import io.github.sejoung.panelyink.core.position.PositionKey
import io.github.sejoung.panelyink.library.LibraryEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * 한 권의 책에 대한 자원 모음. ReaderScreen 라이프사이클과 1:1로 묶여 관리된다.
 *
 * 보유:
 * - 자연 정렬된 [pages] 인덱스
 * - [BitmapPageCache] (PRD §9: 100MB 상한)
 * - 페이지 1장 디코드 함수
 *
 * v1.0 디코더는 `BitmapFactory` 표준 경로. RK3566 성능이 부족하면 NDK
 * libjpeg-turbo / libwebp 로 교체 (PRD §8). SAF 경유라 RandomAccessFile을
 * 못 쓰고 매번 ContentResolver 새 스트림 — O(N) per fetch지만 ±3 프리로드 +
 * LRU 캐시로 평상시 OK.
 */
class CbzBookSession private constructor(
    val bookId: String,
    val pages: List<CbzPage>,
    private val context: Context,
    private val documentUri: Uri,
    private val cache: BitmapPageCache,
) {

    val pageCount: Int get() = pages.size

    /** 캐시에 [pageIndex] 비트맵을 넣는다. 이미 있으면 재사용. */
    suspend fun decode(pageIndex: Int): Bitmap = withContext(Dispatchers.IO) {
        cache.get(pageIndex)?.let { return@withContext it }
        require(pageIndex in pages.indices) { "page $pageIndex out of range [$pageCount]" }
        val bytes = readPageBytes(pageIndex)
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            ?: throw IOException("decode failed for page $pageIndex (${pages[pageIndex].name})")
        cache.put(pageIndex, bitmap)
        bitmap
    }

    /** 동기적으로 캐시 hit만 조회. View.onDraw 같은 메인스레드 핫패스용. */
    fun pageBitmap(pageIndex: Int): Bitmap? = cache.get(pageIndex)

    fun close() {
        cache.clear()
    }

    private fun readPageBytes(pageIndex: Int): ByteArray {
        val loader = CbzLoader {
            context.contentResolver.openInputStream(documentUri)
                ?: throw IOException("cannot open $documentUri")
        }
        return loader.openPage(pages, pageIndex).use { it.readBytes() }
    }

    companion object {
        suspend fun open(context: Context, entry: LibraryEntry): CbzBookSession =
            withContext(Dispatchers.IO) {
                val ctx = context.applicationContext
                val loader = CbzLoader {
                    ctx.contentResolver.openInputStream(entry.documentUri)
                        ?: throw IOException("cannot open ${entry.documentUri}")
                }
                val pages = loader.listPages()
                if (pages.isEmpty()) {
                    throw IOException("이미지 엔트리가 없습니다: ${entry.displayName}")
                }
                CbzBookSession(
                    bookId = PositionKey.bookIdFromUri(entry.documentUri.toString()),
                    pages = pages,
                    context = ctx,
                    documentUri = entry.documentUri,
                    cache = BitmapPageCache(maxBytes = 100L * 1024 * 1024),
                )
            }
    }
}
