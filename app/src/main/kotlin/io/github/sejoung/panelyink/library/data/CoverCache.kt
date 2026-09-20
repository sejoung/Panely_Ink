package io.github.sejoung.panelyink.library.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import java.io.File
import java.io.FileOutputStream

/**
 * 표지 썸네일 디스크 캐시 — `filesDir/covers/<bookId>.jpg`.
 *
 * `filesDir`(외부 cacheDir 아님)을 쓰는 이유: cacheDir은 Android가 임의 시점에 비울 수
 * 있어 라이브러리 첫 진입마다 재추출 비용이 발생한다. 표지는 사용자 데이터의 일부로
 * 보고 filesDir에 둔다. 누적 용량과 orphan 파일은 [CoverPruner]가 정리한다.
 *
 * bookId는 [io.github.sejoung.panelyink.core.position.PositionKey.bookIdFromUri] 결과
 * SHA-1 hex로 파일시스템 안전.
 */
object CoverCache {

  private const val DIR = "covers"
  private const val EXT = "jpg"
  private const val TAG = "PanelyInk.CoverCache"

  fun cacheFile(context: Context, bookId: String): File {
    val dir = File(context.filesDir, DIR).apply { if (!exists()) mkdirs() }
    return File(dir, "$bookId.$EXT")
  }

  /**
   * 디스크에 표지가 있으면 디코드해서 반환, 없거나 깨졌으면 null.
   *
   * hit 시 파일 mtime을 현재 시각으로 갱신 — [CoverPruner]가 mtime을 "마지막 사용 시각"으로
   * 보고 LRU 정리한다(메타의 extracted_at은 추출 시각이라 FIFO가 됨).
   */
  fun loadBitmap(file: File): Bitmap? {
    if (!file.exists() || file.length() == 0L) return null
    return runCatching {
      BitmapFactory.decodeFile(file.absolutePath)
    }.onFailure {
      Log.w(TAG, "load failed: ${file.name}", it)
    }.getOrNull()?.let { bitmap ->
      runCatching { file.setLastModified(System.currentTimeMillis()) }
      // 예전 버전이 저장한 표지는 긴 변이 ~800px까지 될 수 있다 — 메모리에 올릴 때는 현재
      // 목표 크기로 맞춘다(디스크 파일은 그대로, "표지 캐시 비우기"로 재생성 가능).
      runCatching {
        with(CoverExtractor) { bitmap.scaledToMax(CoverExtractor.DEFAULT_TARGET_MAX_PX) }
      }.getOrDefault(bitmap)
    }
  }

  /** JPEG로 저장. 라이브러리 썸네일은 무손실보다 저장 시간/용량이 더 중요하다. */
  fun saveBitmap(file: File, bitmap: Bitmap): Boolean = runCatching {
    FileOutputStream(file).use { out ->
      bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
    }
    true
  }.getOrElse {
    Log.w(TAG, "save failed: ${file.name}", it)
    // 부분 쓰기 방지를 위해 깨진 파일 정리
    runCatching { if (file.exists()) file.delete() }
    false
  }

  private const val JPEG_QUALITY = 86
}
