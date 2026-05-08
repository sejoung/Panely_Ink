package io.github.sejoung.panelyink.reader

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import io.github.sejoung.panelyink.core.archive.CbzArchive
import io.github.sejoung.panelyink.core.archive.CbzPage
import io.github.sejoung.panelyink.core.fit.TrimRect
import io.github.sejoung.panelyink.core.position.PositionKey
import io.github.sejoung.panelyink.core.trim.MarginTrimmer
import io.github.sejoung.panelyink.library.BookEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * 한 권의 책에 대한 자원 모음. ReaderScreen 라이프사이클과 1:1로 묶여 관리된다.
 *
 * 보유:
 * - random-access [CbzArchive] (ZipFile + ParcelFileDescriptor)
 * - [BitmapPageCache] (PRD §9: 100MB 상한)
 * - viewport hint — [decode] 시 inSampleSize 계산에 사용
 *
 * 디코드 파이프라인:
 * 1. `inJustDecodeBounds`로 헤더만 읽어 원본 해상도 파악
 * 2. viewport hint 대비 inSampleSize 계산 (1/2/4/8 …)
 * 3. 다운스케일된 비트맵으로 본 디코드 → 메모리·시간 1/N²
 *
 * RK3566 + Carta 1200(1648×1236) 기준, 3000×4000 페이지가 inSampleSize=2로
 * 1500×2000으로 디코드되면 화면에 그릴 때 `FitCalculator`가 다시 viewport에 맞게
 * 미세 스케일. 추가 다운샘플로 디코드 자체는 1/4 시간.
 */
class CbzBookSession private constructor(
    val bookId: String,
    private val archive: CbzArchive,
    private val cache: BitmapPageCache,
) {

    val pages: List<CbzPage> get() = archive.pages
    val pageCount: Int get() = archive.pages.size

    @Volatile private var hintWidth: Int = 0
    @Volatile private var hintHeight: Int = 0

    /**
     * 페이지별 자동 트리밍 결과. 디코드 시 1회 계산해서 보관 — viewport나 fit이
     * 바뀌어도 같은 비트맵에 대한 본문 좌표는 변하지 않으므로 재계산 불필요.
     */
    private val trimCache = ConcurrentHashMap<Int, TrimRect>()

    /** ReaderView가 onSizeChanged에서 갱신. 0/음수면 무시. */
    fun setViewportHint(width: Int, height: Int) {
        if (width > 0 && height > 0) {
            hintWidth = width
            hintHeight = height
        }
    }

    /** 캐시에 [pageIndex] 비트맵을 넣는다. 이미 있으면 재사용. */
    suspend fun decode(pageIndex: Int): Bitmap = withContext(Dispatchers.IO) {
        cache.get(pageIndex)?.let { return@withContext it }
        require(pageIndex in pages.indices) { "page $pageIndex out of range [$pageCount]" }
        val name = pages[pageIndex].name
        val t0 = System.currentTimeMillis()

        // 1. 헤더만 읽어 원본 크기 파악
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        archive.openPage(pageIndex).use { input ->
            BitmapFactory.decodeStream(input, null, bounds)
        }
        val tBounds = System.currentTimeMillis()
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw IOException("decode bounds failed: $name")
        }

        // 2. viewport에 맞춰 inSampleSize 계산
        val targetW = if (hintWidth > 0) hintWidth else bounds.outWidth
        val targetH = if (hintHeight > 0) hintHeight else bounds.outHeight
        val sample = computeInSampleSize(bounds.outWidth, bounds.outHeight, targetW, targetH)

        // 3. 본 디코드
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888 // RGB565 옵션은 v1.0 후반(또는 PRD §11 Q5) 검토
        }
        val bitmap = archive.openPage(pageIndex).use { input ->
            BitmapFactory.decodeStream(input, null, opts)
        } ?: throw IOException("decode failed: $name")
        val tDecode = System.currentTimeMillis()
        Log.d(
            TAG,
            "decode #$pageIndex $name: bounds=${tBounds - t0}ms decode=${tDecode - tBounds}ms " +
                "src=${bounds.outWidth}x${bounds.outHeight} sample=$sample → ${bitmap.width}x${bitmap.height}",
        )

        cache.put(pageIndex, bitmap)
        // 자동 여백 트리밍(M2) — 같은 IO 스레드에서 한 번만 계산. 1500x2000 픽셀
        // 기준 ~수십 ms로 디코드 자체(100~500ms)에 비하면 작음. 계산이 끝나면
        // 다음 onDraw가 trim을 사용한다.
        if (!trimCache.containsKey(pageIndex)) {
            trimCache[pageIndex] = computeTrim(bitmap)
        }
        bitmap
    }

    /** 동기적으로 캐시 hit만 조회. View.onDraw 같은 메인스레드 핫패스용. */
    fun pageBitmap(pageIndex: Int): Bitmap? = cache.get(pageIndex)

    /** 자동 트리밍 결과(없으면 null). 디코드가 끝난 페이지에 대해 즉시 hit. */
    fun pageTrim(pageIndex: Int): TrimRect? = trimCache[pageIndex]

    private fun computeTrim(bitmap: Bitmap): TrimRect {
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        return MarginTrimmer.detect(pixels, w, h)
    }

    /** [ReaderViewModel] 이 사용하는 [PageDecoder] 어댑터.
     *  viewport 인자를 hint로 받아 다음 디코드부터 다운스케일에 반영한다. */
    fun asDecoder(): PageDecoder = object : PageDecoder {
        override suspend fun decode(
            pageIndex: Int,
            viewportWidth: Int,
            viewportHeight: Int,
        ): DecodedPage {
            setViewportHint(viewportWidth, viewportHeight)
            val bitmap = this@CbzBookSession.decode(pageIndex)
            return DecodedPage(
                pageIndex = pageIndex,
                width = bitmap.width,
                height = bitmap.height,
                payload = bitmap,
            )
        }
    }

    fun close() {
        cache.clear()
        trimCache.clear()
        archive.close()
    }

    companion object {
        private const val TAG = "PanelyInk.Session"

        suspend fun open(context: Context, entry: BookEntry): CbzBookSession =
            withContext(Dispatchers.IO) {
                val ctx = context.applicationContext
                Log.d(TAG, "open ${entry.displayName} (${entry.sizeBytes / 1024} KB) uri=${entry.documentUri}")
                val archive = CbzArchive.open(ctx, entry.documentUri)
                if (archive.pages.isEmpty()) {
                    archive.close()
                    throw IOException("이미지 엔트리가 없습니다: ${entry.displayName}")
                }
                val session = CbzBookSession(
                    bookId = PositionKey.bookIdFromUri(entry.documentUri.toString()),
                    archive = archive,
                    cache = BitmapPageCache(maxBytes = 100L * 1024 * 1024),
                )
                // 첫 디코드는 viewport가 아직 잡히기 전에 일어날 수 있다 — displayMetrics를 fallback으로.
                val dm = ctx.resources.displayMetrics
                session.setViewportHint(dm.widthPixels, dm.heightPixels)
                session
            }
    }
}

/**
 * Android Bitmap loading best practice — viewport보다 작아지지 않을 때까지 2의 거듭제곱으로
 * 다운샘플. inSampleSize=4면 가로/세로 각각 1/4, 픽셀 수는 1/16, 메모리/시간도 그만큼 감소.
 */
private fun computeInSampleSize(srcW: Int, srcH: Int, dstW: Int, dstH: Int): Int {
    if (srcW <= 0 || srcH <= 0 || dstW <= 0 || dstH <= 0) return 1
    var sample = 1
    while ((srcW / (sample * 2)) >= dstW && (srcH / (sample * 2)) >= dstH) {
        sample *= 2
    }
    return sample
}
