package io.github.sejoung.panelyink.library.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import io.github.sejoung.panelyink.core.archive.CbzArchive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * CBZ/ZIP 책의 자연 정렬 첫 페이지를 표지 썸네일 비트맵으로 디코드한다 — M3
 * "표지 자동 추출 + 캐시"의 1차 단계.
 *
 * 비용 메모:
 * - SAF Uri를 [CbzArchive.open] 으로 여는 자체가 무겁다(~수백 ms). 라이브러리에 책
 *   100권이면 누적 비용이 큼. 호출자(`LibraryViewModel`)는 화면에 보이는 책만, 그리고
 *   디스크 캐시 hit 우선이라 실제 추출은 첫 진입 1회에 그친다.
 * - 디코드는 [BitmapFactory.inSampleSize]로 한 변 [targetMaxPx] 이하까지만 다운스케일 —
 *   메모리/시간 1/N². 표지는 만화 본문보다 더 작아도 인식 가능하므로 충분히 작게.
 *
 * 결과는 호출자가 디스크 썸네일로 저장(`CoverCache.saveBitmap`)하고 in-memory에도 유지.
 */
object CoverExtractor {

  /** 7" 라이브러리 행 80×112dp ≈ 200~240px. 4× 안전 마진으로 한 변 400px 기본. */
  const val DEFAULT_TARGET_MAX_PX = 400

  suspend fun extract(
    context: Context,
    documentUri: Uri,
    targetMaxPx: Int = DEFAULT_TARGET_MAX_PX,
  ): Bitmap? = withContext(Dispatchers.IO) {
    val archive = runCatching {
      CbzArchive.open(context, documentUri)
    }.getOrElse {
      Log.w(TAG, "open failed: $documentUri", it)
      return@withContext null
    }
    try {
      if (archive.pages.isEmpty()) return@withContext null
      decodeFirstPage(archive, targetMaxPx)
    } catch (t: Throwable) {
      Log.w(TAG, "decode failed: $documentUri", t)
      null
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
  ): Bitmap? = withContext(Dispatchers.IO) {
    val archive = runCatching {
      CbzArchive.open(file)
    }.getOrElse {
      Log.w(TAG, "open(file) failed: ${file.name}", it)
      return@withContext null
    }
    try {
      if (archive.pages.isEmpty()) return@withContext null
      decodeFirstPage(archive, targetMaxPx)
    } catch (t: Throwable) {
      Log.w(TAG, "decode(file) failed: ${file.name}", t)
      null
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
  ): Bitmap? = withContext(Dispatchers.IO) {
    val archive = runCatching {
      CbzArchive.open(context, parentZipUri)
    }.getOrElse {
      Log.w(TAG, "nested cover open failed: $parentZipUri", it)
      return@withContext null
    }
    try {
      val bytes = archive.firstImageBytesInNested(nestedEntryName)
        ?: return@withContext null
      decodeBytes(bytes, targetMaxPx)
    } catch (t: Throwable) {
      Log.w(TAG, "nested cover decode failed: $parentZipUri / $nestedEntryName", t)
      null
    } finally {
      archive.close()
    }
  }

  private fun decodeBytes(bytes: ByteArray, targetMaxPx: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (
      bounds.outWidth / (sample * 2) >= targetMaxPx ||
      bounds.outHeight / (sample * 2) >= targetMaxPx
    ) {
      sample *= 2
    }
    val opts = BitmapFactory.Options().apply {
      inSampleSize = sample
      inPreferredConfig = Bitmap.Config.ARGB_8888
    }
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
  }

  private fun decodeFirstPage(archive: CbzArchive, targetMaxPx: Int): Bitmap? {
    // 1. 헤더만 읽어 원본 크기 파악
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    archive.openPage(0).use { input ->
      BitmapFactory.decodeStream(input, null, bounds)
    }
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

    // 2. inSampleSize 계산 — 한 변이 targetMaxPx 이하가 될 때까지 2의 거듭제곱 다운샘플
    var sample = 1
    while (
      bounds.outWidth / (sample * 2) >= targetMaxPx ||
      bounds.outHeight / (sample * 2) >= targetMaxPx
    ) {
      sample *= 2
    }

    // 3. 본 디코드 — 표지는 본문 캐시(BitmapPageCache, 100MB)와 별개로 작게.
    val opts = BitmapFactory.Options().apply {
      inSampleSize = sample
      inPreferredConfig = Bitmap.Config.ARGB_8888
    }
    return archive.openPage(0).use { input ->
      BitmapFactory.decodeStream(input, null, opts)
    }
  }

  private const val TAG = "PanelyInk.CoverExtractor"
}
