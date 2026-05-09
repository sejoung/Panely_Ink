package io.github.sejoung.panelyink.library.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import java.io.File
import java.io.FileOutputStream

/**
 * 표지 썸네일 디스크 캐시 — `filesDir/covers/<bookId>.png`.
 *
 * `filesDir`(외부 cacheDir 아님)을 쓰는 이유: cacheDir은 Android가 임의 시점에 비울 수
 * 있어 라이브러리 첫 진입마다 재추출 비용이 발생한다. 표지는 사용자 데이터의 일부로
 * 보고 filesDir에 두되, 추후 [io.github.sejoung.panelyink.library.data.LibraryRepository]
 * 측에서 LRU 정리 정책(M3 후반 항목)을 추가한다.
 *
 * bookId는 [io.github.sejoung.panelyink.core.position.PositionKey.bookIdFromUri] 결과
 * SHA-1 hex로 파일시스템 안전.
 */
object CoverCache {

  private const val DIR = "covers"
  private const val EXT = "png"
  private const val TAG = "PanelyInk.CoverCache"

  fun cacheFile(context: Context, bookId: String): File {
    val dir = File(context.filesDir, DIR).apply { if (!exists()) mkdirs() }
    return File(dir, "$bookId.$EXT")
  }

  /** 디스크에 표지가 있으면 디코드해서 반환, 없거나 깨졌으면 null. */
  fun loadBitmap(file: File): Bitmap? {
    if (!file.exists() || file.length() == 0L) return null
    return runCatching {
      BitmapFactory.decodeFile(file.absolutePath)
    }.onFailure {
      Log.w(TAG, "load failed: ${file.name}", it)
    }.getOrNull()
  }

  /** PNG로 무손실 압축 저장. 200KB 미만이라 압축 시간은 무시할 수준. */
  fun saveBitmap(file: File, bitmap: Bitmap): Boolean = runCatching {
    FileOutputStream(file).use { out ->
      bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
    }
    true
  }.getOrElse {
    Log.w(TAG, "save failed: ${file.name}", it)
    // 부분 쓰기 방지를 위해 깨진 파일 정리
    runCatching { if (file.exists()) file.delete() }
    false
  }
}
