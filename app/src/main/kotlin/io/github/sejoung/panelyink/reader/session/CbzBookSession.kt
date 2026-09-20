package io.github.sejoung.panelyink.reader.session

import android.content.Context
import android.app.ActivityManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
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
import java.lang.ref.WeakReference
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

  /** [hintWidth]/[hintHeight]의 일관된 스냅샷/갱신을 위한 락. */
  private val resolutionLock = Any()

  /**
   * 페이지별 디코드 메타 — 원본 크기, 디코드에 쓴 inSampleSize, 자동 트리밍 결과.
   *
   * 캐시 키는 pageIndex뿐이라 해상도가 바뀌는 변경(두쪽↔단쪽 토글, 단독 슬롯↔페어, 회전) 후에도
   * 이전 해상도 비트맵이 그대로 반환될 수 있다. 예전에는 viewport hint가 바뀔 때마다 캐시를 통째로
   * 비웠는데, 두쪽 보기에서 단독 슬롯(전체 폭)과 페어(절반 폭)를 오갈 때마다 프리로드가 전부 버려지고
   * 빈 화면이 한 번 그려지는 문제가 있었다. 지금은 캐시를 비우지 않고, [decode]가 hit 시점에
   * "현재 hint에 필요한 sample보다 거칠게 디코드된 비트맵인가"만 판정해 그 페이지만 재디코드한다.
   * 더 곱게 디코드된 비트맵은 그대로 재사용(메모리만 조금 더 쓰고 화질 손해 없음).
   *
   * 메타는 [PageMeta.bitmap]의 identity로 비트맵에 묶인다 — 트림 좌표는 비트맵 해상도에 종속되므로
   * 다른 해상도의 비트맵에 stale 트림이 짝지어지면 안 된다. WeakReference라 LRU eviction을 막지 않는다.
   */
  private class PageMeta(
    val bitmap: WeakReference<Bitmap>,
    val srcWidth: Int,
    val srcHeight: Int,
    val sample: Int,
    val bytesPerPixel: Int,
    @Volatile var trim: TrimRect? = null,
  )

  private val pageMeta = ConcurrentHashMap<Int, PageMeta>()

  /** 같은 페이지의 동시 put(취소된 디코드의 뒤늦은 결과 vs 새 디코드)을 직렬화. */
  private val putLock = Any()

  /**
   * 세션이 닫혔는지 표시. close 직후 in-flight 디코드가 archive를 사용 중이다가
   * IOException을 받으면 [decode]가 [CancellationException]으로 변환해 propagate한다.
   * preloadJob은 이미 cancel 상태라 silent 처리.
   */
  @Volatile
  private var sessionClosed = false

  /**
   * ReaderView가 onSizeChanged에서, 그리고 디코드 직전 [asDecoder]가 갱신. 0/음수면 무시.
   *
   * 캐시는 비우지 않는다 — 해상도가 부족한 페이지는 [decode]가 [PageMeta.sample]로 판정해 개별 재디코드.
   */
  fun setViewportHint(width: Int, height: Int) {
    if (width <= 0 || height <= 0) return
    synchronized(resolutionLock) {
      hintWidth = width
      hintHeight = height
    }
  }

  /**
   * 캐시에 [pageIndex] 비트맵을 넣는다. 현재 viewport hint에 충분한 해상도로 이미 있으면 재사용.
   *
   * **병렬 호출 가능 (v1.1):** [CbzArchive]가 N개 ZipFile reader pool을 유지하므로
   * 두 디코드가 서로 다른 reader를 빌려 동시에 IO/디코드 가능. 같은 page index에 대한 중복 호출은
   * 둘 다 cache miss → 둘 다 디코드 → [putLock] 아래에서 더 고운 쪽이 살아남는다. ReaderViewModel은
   * 각 디코드 호출 인덱스가 다르도록 구성하므로 실제 중복은 취소된 디코드의 뒤늦은 결과뿐.
   *
   * 세션이 close 되는 중 archive read가 IOException을 던지면 [CancellationException]으로 변환해
   * preloadJob의 정상 cancel 경로로 합류시킨다. 그 외 디코드 실패(손상 이미지, IO 오류, OOM)는
   * [IOException]으로 던진다 — 호출자가 페이지 단위 실패로 처리.
   */
  suspend fun decode(pageIndex: Int, trimEnabled: Boolean = true): Bitmap {
    val (w, h) = synchronized(resolutionLock) { hintWidth to hintHeight }
    return decodeAt(pageIndex, trimEnabled, w, h)
  }

  private suspend fun decodeAt(
    pageIndex: Int,
    trimEnabled: Boolean,
    viewportWidth: Int,
    viewportHeight: Int,
  ): Bitmap = withContext(Dispatchers.IO) {
    if (sessionClosed) throw CancellationException("session closed")
    cachedPage(pageIndex)?.let { (bitmap, meta) ->
      val needed = targetSample(
        meta.srcWidth, meta.srcHeight, viewportWidth, viewportHeight, meta.bytesPerPixel,
      )
      if (meta.sample <= needed) {
        if (trimEnabled && meta.trim == null) meta.trim = computeTrim(bitmap)
        return@withContext bitmap
      }
      // 더 거칠게 디코드된 비트맵(예: 두쪽 페어용 ½ 해상도) — 아래에서 현재 해상도로 재디코드해 교체.
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
      // JPEG는 알파가 없어 RGB_565(2byte)가 항상 적용된다. 그 외(PNG/WebP)는 ARGB_8888 폴백을 가정.
      val bytesPerPixel = if (bounds.outMimeType == "image/jpeg") 2 else 4
      val sample = targetSample(
        bounds.outWidth, bounds.outHeight, viewportWidth, viewportHeight, bytesPerPixel,
      )

      // 3. 본 디코드 — RGB_565로 메모리 절반(ARGB_8888 4byte → RGB_565 2byte/픽셀).
      //
      // 만화/만화 페이지는 흑백 또는 제한된 컬러 팔레트라 RGB_565의 5-6-5 비트 깊이로 시각적 차이 없음.
      // ContrastMatrix/InvertMatrix는 ColorMatrixColorFilter로 draw 시점에 적용되므로 bitmap config와 무관.
      //
      // 효과 (1500×2000 픽셀 기준):
      // - ARGB_8888: 12MB / 페이지. ±3 프리로드 7장 = 84MB. 64MB 캐시에서 2-3장 evict — spread 모드에서
      //   다음 spread (N+2, N+3) 페이지가 evict 후보가 되어 페이지 넘김 시 빈 페이지 발생.
      // - RGB_565: 6MB / 페이지. 7장 = 42MB. 64MB 캐시에 모두 fit — 이웃 spread 즉시 hit, 빈 페이지 0.
      //
      // 알파 채널이 필요한 PNG는 BitmapFactory가 자동으로 ARGB_8888로 폴백 (inPreferredConfig는 hint).
      val opts = BitmapFactory.Options().apply {
        inSampleSize = sample
        inPreferredConfig = Bitmap.Config.RGB_565
      }
      val bitmap = try {
        archive.openPage(pageIndex).use { input ->
          BitmapFactory.decodeStream(input, null, opts)
        }
      } catch (oom: OutOfMemoryError) {
        // 캐시를 비워 회복 여지를 만들고 페이지 단위 실패로 보고 — 프로세스를 죽이지 않는다.
        cache.clear()
        throw IOException("out of memory decoding $name", oom)
      } ?: throw IOException("decode failed: $name")
      val tDecode = System.currentTimeMillis()
      Log.d(
        TAG,
        "decode #$pageIndex $name: bounds=${tBounds - t0}ms decode=${tDecode - tBounds}ms " +
          "src=${bounds.outWidth}x${bounds.outHeight} sample=$sample → ${bitmap.width}x${bitmap.height}",
      )

      val meta = PageMeta(
        WeakReference(bitmap), bounds.outWidth, bounds.outHeight, sample, bytesPerPixel,
      )
      // 자동 여백 트리밍은 사용자가 켠 경우에만 계산한다. 꺼진 상태에서는 행 버퍼 IntArray
      // 할당과 픽셀 스캔을 생략해 페이지 전환 비용을 줄인다.
      if (trimEnabled) meta.trim = computeTrim(bitmap)
      synchronized(putLock) {
        // 취소된 이전 디코드(더 거친 해상도)가 뒤늦게 끝나 더 고운 비트맵을 덮어쓰지 않게 한다.
        val existing = cachedPage(pageIndex)
        if (existing == null || existing.second.sample > sample) {
          cache.put(pageIndex, bitmap)
          pageMeta[pageIndex] = meta
        }
      }
      bitmap
    } catch (io: IOException) {
      // close가 archive를 끊으면서 발생한 IOException은 cancel 경로로 정상화.
      if (sessionClosed) throw CancellationException("session closed during decode #$pageIndex").initCause(io)
      throw io
    }
  }

  /**
   * viewport에 맞춘 inSampleSize에, 비트맵 1장이 페이지 캐시의 절반을 넘지 않도록 하는 상한을 더한다.
   * 세로로 긴 웹툰 스트립(예: 1200×21000)은 [computeInSampleSize]가 두 변 모두 viewport 이상일 때만
   * 줄이므로 sample=1로 남는데, 그대로 디코드하면 캐시 상한을 넘어 put 즉시 evict되어 영영 빈 페이지가 된다.
   */
  private fun targetSample(
    srcW: Int,
    srcH: Int,
    viewportWidth: Int,
    viewportHeight: Int,
    bytesPerPixel: Int,
  ): Int {
    val targetW = if (viewportWidth > 0) viewportWidth else srcW
    val targetH = if (viewportHeight > 0) viewportHeight else srcH
    var sample = computeInSampleSize(srcW, srcH, targetW, targetH)
    val budget = cache.maxBytes / 2
    while ((srcW.toLong() / sample) * (srcH.toLong() / sample) * bytesPerPixel > budget && sample < MAX_SAMPLE) {
      sample *= 2
    }
    return sample
  }

  /** 캐시에 살아 있는 비트맵과 그 비트맵에 묶인 메타. 메타가 다른 비트맵의 것이면 null. */
  private fun cachedPage(pageIndex: Int): Pair<Bitmap, PageMeta>? {
    val bitmap = cache.get(pageIndex) ?: return null
    val meta = pageMeta[pageIndex]?.takeIf { it.bitmap.get() === bitmap } ?: return null
    return bitmap to meta
  }

  /** 동기적으로 캐시 hit만 조회. View.onDraw 같은 메인스레드 핫패스용. */
  fun pageBitmap(pageIndex: Int): Bitmap? = cache.get(pageIndex)

  /**
   * [bitmap]에 대한 자동 트리밍 결과(없으면 null). [pageBitmap]으로 받은 비트맵을 그대로 넘긴다 —
   * 그 사이 같은 페이지가 다른 해상도로 교체됐으면 좌표계가 달라 null을 돌려준다.
   */
  fun pageTrim(pageIndex: Int, bitmap: Bitmap): TrimRect? =
    pageMeta[pageIndex]?.takeIf { it.bitmap.get() === bitmap }?.trim

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
      val bitmap = decodeAt(pageIndex, trimEnabled, viewportWidth, viewportHeight)
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
    pageMeta.clear()
    archive.close()
  }

  companion object {
    private const val TAG = "PanelyInk.Session"

    suspend fun open(context: Context, entry: BookRef): CbzBookSession {
      // withContext(IO) 블록은 blocking IO라 취소돼도 끝까지 돌아 세션을 만든다. 그 뒤 withContext가
      // 결과 대신 CancellationException을 던지면 호출자는 세션을 받지 못해 닫을 수 없다(PFD/ZipFile 누수).
      // 블록이 만든 세션을 여기서 잡아 두었다가 취소 시 직접 닫는다.
      var opened: CbzBookSession? = null
      try {
        return withContext(Dispatchers.IO) { openOnIo(context, entry).also { opened = it } }
      } catch (cancel: CancellationException) {
        opened?.close()
        throw cancel
      }
    }

    /** 추출본을 열어 페이지가 있는 archive를 돌려준다. 열기 실패/빈 archive면 null([rethrow]면 그대로 던짐). */
    private suspend fun openNested(
      ctx: Context,
      parentUri: Uri,
      entryName: String,
      rethrow: Boolean = false,
    ): CbzArchive? {
      val tempFile = NestedZipExtractor.extract(ctx, parentUri, entryName)
      return try {
        // 두쪽 보기 병렬 디코드를 위해 reader 2개. spread 미사용 시도 60-100ms 초과 비용일 뿐.
        val archive = CbzArchive.open(tempFile, parallelReaders = CbzArchive.PARALLEL_READERS_FOR_SPREAD)
        if (archive.pages.isEmpty() && !rethrow) {
          archive.close()
          null
        } else {
          archive
        }
      } catch (io: IOException) {
        if (rethrow) throw io
        null
      }
    }

    private suspend fun openOnIo(context: Context, entry: BookRef): CbzBookSession {
      val ctx = context.applicationContext
      Log.d(
        TAG,
        "open ${entry.displayName} (${entry.sizeBytes / 1024} KB) " +
          "uri=${entry.documentUri} nested=${entry.nestedEntryName ?: "-"}",
      )
      val archive = if (entry.nestedEntryName != null) {
        // ZIP-of-CBZ 자식 — 부모 ZIP에서 추출 후 단일 cbz로 open.
        // 캐시된 추출본이 손상돼 열리지 않으면(구버전이 남긴 잘린 파일 등) 지우고 1회만 재추출한다 —
        // 그대로 두면 전체 초기화 전까지 그 권을 영영 열 수 없다.
        openNested(ctx, entry.documentUri, entry.nestedEntryName)
          ?: run {
            Log.w(TAG, "cached nested extraction unreadable, re-extracting: ${entry.nestedEntryName}")
            NestedZipExtractor.invalidate(ctx, entry.documentUri, entry.nestedEntryName)
            openNested(ctx, entry.documentUri, entry.nestedEntryName, rethrow = true)!!
          }
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
      return session
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

/** 캐시 상한 보호용 다운샘플의 안전 상한. */
private const val MAX_SAMPLE = 64

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
