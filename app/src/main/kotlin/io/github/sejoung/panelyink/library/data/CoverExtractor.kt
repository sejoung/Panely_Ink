package io.github.sejoung.panelyink.library.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import io.github.sejoung.panelyink.core.archive.CbzArchive
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.zip.ZipException

/**
 * CBZ/ZIP 책의 자연 정렬 첫 페이지를 표지 썸네일 비트맵으로 디코드한다 — M3
 * "표지 자동 추출 + 캐시"의 1차 단계.
 *
 * 비용 메모:
 * - SAF Uri를 [CbzArchive.open] 으로 여는 자체가 무겁다(~수백 ms). 라이브러리에 책
 *   100권이면 누적 비용이 큼. 호출자(`LibraryViewModel`)는 화면에 보이는 책만, 그리고
 *   디스크 캐시 hit 우선이라 실제 추출은 첫 진입 1회에 그친다.
 * - 디코드는 [BitmapFactory.inSampleSize]로 먼저 거칠게 줄인 뒤(메모리/시간 1/N²) 긴 변이
 *   정확히 [targetMaxPx] 이하가 되도록 한 번 더 스케일. 표지는 만화 본문보다 더 작아도
 *   인식 가능하므로 충분히 작게.
 *
 * 결과는 호출자가 디스크 썸네일로 저장(`CoverCache.saveBitmap`)하고 in-memory에도 유지.
 *
 * 실패는 두 종류로 구분해 돌려준다([CoverExtraction]) — 호출자가 FAILED 메타를 영구
 * 저장해도 되는 경우(책 자체에 표지로 쓸 이미지가 없음/손상)와 다음에 다시 시도해야 하는
 * 경우(IO 오류, OOM 등 일시적 원인)를 가르기 위함.
 */
object CoverExtractor {

  /** 7" 라이브러리 행 80×112dp ≈ 200~240px. 4× 안전 마진으로 한 변 400px 기본. */
  const val DEFAULT_TARGET_MAX_PX = 400

  suspend fun extract(
    context: Context,
    documentUri: Uri,
    targetMaxPx: Int = DEFAULT_TARGET_MAX_PX,
  ): CoverExtraction = withContext(Dispatchers.IO) {
    val archive = try {
      CbzArchive.open(context, documentUri)
    } catch (t: Throwable) {
      Log.w(TAG, "open failed: $documentUri", t)
      return@withContext classifyFailure(t)
    }
    try {
      if (archive.pages.isEmpty()) return@withContext CoverExtraction.NoCover
      decodeFirstPage(archive, targetMaxPx).toExtraction()
    } catch (t: Throwable) {
      Log.w(TAG, "decode failed: $documentUri", t)
      classifyFailure(t)
    } finally {
      archive.close()
    }
  }

  /**
   * ZIP-of-CBZ nested 책용 — 이미 추출된 cbz [file]에서 첫 페이지를 디코드. nested 책이
   * reader에서 한 번 열린 적이 있어 [NestedZipExtractor]가 cacheDir에 추출해 둔 경우만
   * 이 경로가 의미있다.
   */
  suspend fun extract(
    file: java.io.File,
    targetMaxPx: Int = DEFAULT_TARGET_MAX_PX,
  ): CoverExtraction = withContext(Dispatchers.IO) {
    val archive = try {
      CbzArchive.open(file)
    } catch (t: Throwable) {
      Log.w(TAG, "open(file) failed: ${file.name}", t)
      return@withContext classifyFailure(t)
    }
    try {
      if (archive.pages.isEmpty()) return@withContext CoverExtraction.NoCover
      decodeFirstPage(archive, targetMaxPx).toExtraction()
    } catch (t: Throwable) {
      Log.w(TAG, "decode(file) failed: ${file.name}", t)
      classifyFailure(t)
    } finally {
      archive.close()
    }
  }

  /**
   * ZIP-of-CBZ 가상 폴더 표지용 — 부모 ZIP을 한 번 열고, 그 안의 nested cbz를 streaming
   * 으로 읽어 첫 image entry의 bytes를 메모리에서 디코드. 디스크 추출 0.
   *
   * @param parentZipUri 부모 ZIP의 SAF URI
   * @param nestedEntryName 부모 ZIP 안의 nested cbz/zip entry 이름(보통 첫 nested)
   */
  suspend fun extractNestedCover(
    context: Context,
    parentZipUri: Uri,
    nestedEntryName: String,
    targetMaxPx: Int = DEFAULT_TARGET_MAX_PX,
  ): CoverExtraction = withContext(Dispatchers.IO) {
    val archive = try {
      CbzArchive.open(context, parentZipUri)
    } catch (t: Throwable) {
      Log.w(TAG, "nested cover open failed: $parentZipUri", t)
      return@withContext classifyFailure(t)
    }
    try {
      val bytes = archive.firstImageBytesInNested(nestedEntryName)
        ?: return@withContext CoverExtraction.NoCover
      decodeBytes(bytes, targetMaxPx).toExtraction()
    } catch (t: Throwable) {
      Log.w(TAG, "nested cover decode failed: $parentZipUri / $nestedEntryName", t)
      classifyFailure(t)
    } finally {
      archive.close()
    }
  }

  /** 디코드까지 갔는데 null = 첫 이미지가 디코드 불가(손상/미지원 포맷) → 결정적 실패. */
  private fun Bitmap?.toExtraction(): CoverExtraction =
    if (this != null) CoverExtraction.Success(this) else CoverExtraction.NoCover

  /**
   * 실패 분류. 손상된 ZIP/entry([ZipException])만 결정적 — 파일 없음/권한/SD 언마운트 같은
   * IO 오류와 OOM은 일시적일 수 있어 [CoverExtraction.TransientFailure]. 취소는 그대로 전파.
   */
  private fun classifyFailure(t: Throwable): CoverExtraction {
    if (t is CancellationException) throw t
    return if (t is ZipException) CoverExtraction.NoCover else CoverExtraction.TransientFailure
  }

  private fun decodeBytes(bytes: ByteArray, targetMaxPx: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    val opts = BitmapFactory.Options().apply {
      inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, targetMaxPx)
      inPreferredConfig = Bitmap.Config.ARGB_8888
    }
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
      ?.scaledToMax(targetMaxPx)
  }

  /**
   * 2의 거듭제곱 다운샘플 — 결과의 긴 변이 [targetMaxPx] **이상**으로 남는 가장 큰 sample.
   * (결과 긴 변은 targetMaxPx..2×targetMaxPx 미만) 이후 [scaledToMax]가 정확히 맞춘다.
   * sample만으로 targetMaxPx 이하까지 내리면 긴 변이 target의 절반까지 떨어질 수 있어
   * Grid 표시에서 뭉개진다.
   */
  internal fun sampleSizeFor(width: Int, height: Int, targetMaxPx: Int): Int {
    var sample = 1
    while (maxOf(width, height) / (sample * 2) >= targetMaxPx) {
      sample *= 2
    }
    return sample
  }

  /**
   * 긴 변을 [targetMaxPx] 이하로 맞춘다. 이전엔 sample 단계에서 멈춰 최대 ~800px 비트맵이
   * 그대로 state/디스크에 들어갔다(문서상 400px의 최대 4배 메모리).
   */
  internal fun Bitmap.scaledToMax(targetMaxPx: Int): Bitmap {
    val longSide = maxOf(width, height)
    if (longSide <= targetMaxPx) return this
    val scale = targetMaxPx.toFloat() / longSide
    val w = (width * scale).toInt().coerceAtLeast(1)
    val h = (height * scale).toInt().coerceAtLeast(1)
    val scaled = Bitmap.createScaledBitmap(this, w, h, true)
    if (scaled !== this) recycle()
    return scaled
  }

  private fun decodeFirstPage(archive: CbzArchive, targetMaxPx: Int): Bitmap? {
    // 1. 헤더만 읽어 원본 크기 파악
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    archive.openPage(0).use { input ->
      BitmapFactory.decodeStream(input, null, bounds)
    }
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

    // 2. inSampleSize 계산 — 2의 거듭제곱 다운샘플([sampleSizeFor])
    // 3. 본 디코드 — 표지는 본문 캐시(BitmapPageCache, 100MB)와 별개로 작게.
    val opts = BitmapFactory.Options().apply {
      inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, targetMaxPx)
      inPreferredConfig = Bitmap.Config.ARGB_8888
    }
    // 4. 긴 변을 targetMaxPx 이하로 정확히 맞춤
    return archive.openPage(0).use { input ->
      BitmapFactory.decodeStream(input, null, opts)
    }?.scaledToMax(targetMaxPx)
  }

  private const val TAG = "PanelyInk.CoverExtractor"
}

/** [CoverExtractor] 결과. */
sealed interface CoverExtraction {
  data class Success(val bitmap: Bitmap) : CoverExtraction

  /** 결정적 실패 — 책에 표지로 쓸 이미지가 없거나 손상. FAILED 메타를 영구 저장해도 된다. */
  data object NoCover : CoverExtraction

  /** 일시적일 수 있는 실패(IO 오류, OOM 등) — FAILED로 굳히지 말고 다음 기회에 재시도. */
  data object TransientFailure : CoverExtraction
}
