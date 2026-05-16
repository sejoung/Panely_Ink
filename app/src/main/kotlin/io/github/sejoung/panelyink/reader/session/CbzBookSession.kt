package io.github.sejoung.panelyink.reader.session

import android.content.Context
import android.app.ActivityManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import io.github.sejoung.panelyink.R
import io.github.sejoung.panelyink.core.archive.CbzArchive
import io.github.sejoung.panelyink.core.archive.CbzPage
import io.github.sejoung.panelyink.core.archive.NestedZipExtractor
import io.github.sejoung.panelyink.core.book.BookRef
import io.github.sejoung.panelyink.core.fit.TrimRect
import io.github.sejoung.panelyink.core.trim.MarginTrimmer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * 한 권의 책에 대한 자원 모음. ReaderScreen 라이프사이클과 1:1로 묶여 관리된다.
 *
 * 보유:
 * - random-access [CbzArchive] (ZipFile + ParcelFileDescriptor)
 * - [BitmapPageCache] (기기 메모리 등급 기반 상한)
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

  @Volatile
  private var hintWidth: Int = 0
  @Volatile
  private var hintHeight: Int = 0

  /**
   * 페이지별 자동 트리밍 결과. 디코드 시 1회 계산해서 보관 — viewport나 fit이
   * 바뀌어도 같은 비트맵에 대한 본문 좌표는 변하지 않으므로 재계산 불필요.
   */
  private val trimCache = ConcurrentHashMap<Int, TrimRect>()

  /**
   * 세션이 닫혔는지 표시. close 직후 in-flight 디코드가 archive를 사용 중이다가
   * IOException을 받으면 [decode]가 [CancellationException]으로 변환해 propagate한다.
   * preloadJob은 이미 cancel 상태라 silent 처리.
   */
  @Volatile
  private var sessionClosed = false

  /** ReaderView가 onSizeChanged에서 갱신. 0/음수면 무시. */
  fun setViewportHint(width: Int, height: Int) {
    if (width > 0 && height > 0) {
      hintWidth = width
      hintHeight = height
    }
  }

  /**
   * 캐시에 [pageIndex] 비트맵을 넣는다. 이미 있으면 재사용.
   *
   * **병렬 호출 가능 (v1.1):** [CbzArchive]가 dup PFD로 N개 독립 ZipFile reader pool을 유지하므로
   * 두 디코드가 서로 다른 reader를 빌려 동시에 IO/디코드 가능. 같은 page index에 대한 중복 호출은
   * 둘 다 cache miss → 둘 다 디코드 → 둘 다 put (idempotent, 마지막이 살아남음). ReaderViewModel은
   * 각 디코드 호출 인덱스가 다르도록 구성하므로 실제 중복은 없음.
   *
   * 세션이 close 되는 중 archive read가 IOException을 던지면 [CancellationException]으로 변환해
   * preloadJob의 정상 cancel 경로로 합류시킨다.
   */
  suspend fun decode(pageIndex: Int, trimEnabled: Boolean = true): Bitmap = withContext(Dispatchers.IO) {
    if (sessionClosed) throw CancellationException("session closed")
    cache.get(pageIndex)?.let { bitmap ->
      if (trimEnabled && !trimCache.containsKey(pageIndex)) {
        trimCache[pageIndex] = computeTrim(bitmap)
      }
      return@withContext bitmap
    }
    require(pageIndex in pages.indices) { "page $pageIndex out of range [$pageCount]" }
    val name = pages[pageIndex].name
    val t0 = System.currentTimeMillis()

    try {
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
      // 자동 여백 트리밍은 사용자가 켠 경우에만 계산한다. 꺼진 상태에서는 행 버퍼 IntArray
      // 할당과 픽셀 스캔을 생략해 페이지 전환 비용을 줄인다.
      if (trimEnabled && !trimCache.containsKey(pageIndex)) {
        trimCache[pageIndex] = computeTrim(bitmap)
      }
      bitmap
    } catch (io: IOException) {
      // close가 archive를 끊으면서 발생한 IOException은 cancel 경로로 정상화.
      if (sessionClosed) throw CancellationException("session closed during decode #$pageIndex").initCause(io)
      throw io
    }
  }

  /** 동기적으로 캐시 hit만 조회. View.onDraw 같은 메인스레드 핫패스용. */
  fun pageBitmap(pageIndex: Int): Bitmap? = cache.get(pageIndex)

  /** 자동 트리밍 결과(없으면 null). 디코드가 끝난 페이지에 대해 즉시 hit. */
  fun pageTrim(pageIndex: Int): TrimRect? = trimCache[pageIndex]

  private fun computeTrim(bitmap: Bitmap): TrimRect {
    val w = bitmap.width
    val h = bitmap.height
    // 한 행씩 getPixels로 채워 검사 — 전체 픽셀 IntArray(2000×3000=24MB)를 한 번에
    // 할당하지 않는다. RK3566/3GB에서 GC pause로 인한 e-ink 깜빡임 방지.
    return MarginTrimmer.detect(
      rowProvider = { y, buffer ->
        bitmap.getPixels(buffer, 0, w, 0, y, w, 1)
      },
      width = w,
      height = h,
    )
  }

  /** [ReaderViewModel] 이 사용하는 [PageDecoder] 어댑터.
   *  viewport 인자를 hint로 받아 다음 디코드부터 다운스케일에 반영한다. */
  fun asDecoder(): PageDecoder = object : PageDecoder {
    override suspend fun decode(
      pageIndex: Int,
      viewportWidth: Int,
      viewportHeight: Int,
      trimEnabled: Boolean,
    ): DecodedPage {
      setViewportHint(viewportWidth, viewportHeight)
      val bitmap = this@CbzBookSession.decode(pageIndex, trimEnabled)
      return DecodedPage(
        pageIndex = pageIndex,
        width = bitmap.width,
        height = bitmap.height,
        payload = bitmap,
      )
    }

    override fun keepWarm(visibleIndices: IntArray) {
      // cache.get은 LruCache의 access 타임스탬프를 갱신해 evict 우선순위를 가장 낮춤.
      // 캐시에 없으면 null 반환만 하고 LRU에 영향 없음 — 안전.
      visibleIndices.forEach { idx -> cache.get(idx) }
    }
  }

  fun close() {
    // v1.1: 디코드 동시성을 풀어 mutex 제거. archive.close()는 in-flight reader도 강제로 끊지만
    // [decode]가 IOException을 [CancellationException]으로 변환해 cancel 경로로 합류시키므로 안전.
    // preloadJob은 viewModel.scope.cancel()로 이미 cancel 시그널을 받은 상태.
    sessionClosed = true
    cache.clear()
    trimCache.clear()
    archive.close()
  }

  companion object {
    private const val TAG = "PanelyInk.Session"

    suspend fun open(context: Context, entry: BookRef): CbzBookSession =
      withContext(Dispatchers.IO) {
        val ctx = context.applicationContext
        Log.d(
          TAG,
          "open ${entry.displayName} (${entry.sizeBytes / 1024} KB) " +
            "uri=${entry.documentUri} nested=${entry.nestedEntryName ?: "-"}",
        )
        val archive = if (entry.nestedEntryName != null) {
          // ZIP-of-CBZ 자식 — 부모 ZIP에서 추출 후 단일 cbz로 open
          val tempFile = NestedZipExtractor.extract(
            ctx, entry.documentUri, entry.nestedEntryName,
          )
          // 두쪽 보기 병렬 디코드를 위해 reader 2개. spread 미사용 시도 60-100ms 초과 비용일 뿐.
          CbzArchive.open(tempFile, parallelReaders = CbzArchive.PARALLEL_READERS_FOR_SPREAD)
        } else {
          CbzArchive.open(ctx, entry.documentUri, parallelReaders = CbzArchive.PARALLEL_READERS_FOR_SPREAD)
        }
        if (archive.pages.isEmpty()) {
          archive.close()
          throw IOException(ctx.getString(R.string.reader_error_no_images, entry.displayName))
        }
        val session = CbzBookSession(
          bookId = entry.bookId.value,
          archive = archive,
          cache = BitmapPageCache(maxBytes = pageCacheMaxBytes(ctx)),
        )
        // 첫 디코드는 viewport가 아직 잡히기 전에 일어날 수 있다 — displayMetrics를 fallback으로.
        val dm = ctx.resources.displayMetrics
        session.setViewportHint(dm.widthPixels, dm.heightPixels)
        session
      }
  }
}

private fun pageCacheMaxBytes(context: Context): Long {
  val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
  val memoryClassMb = manager?.memoryClass ?: DEFAULT_MEMORY_CLASS_MB
  val fractionMb = (memoryClassMb / 4).coerceAtLeast(MIN_PAGE_CACHE_MB)
  val capMb = if (manager?.isLowRamDevice == true) LOW_RAM_PAGE_CACHE_MB else MAX_PAGE_CACHE_MB
  return minOf(fractionMb, capMb) * 1024L * 1024L
}

private const val DEFAULT_MEMORY_CLASS_MB = 256
private const val MIN_PAGE_CACHE_MB = 32
private const val LOW_RAM_PAGE_CACHE_MB = 48
private const val MAX_PAGE_CACHE_MB = 100

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
