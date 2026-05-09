package io.github.sejoung.panelyink.library.data

import android.content.Context
import android.util.Log
import io.github.sejoung.panelyink.data.db.cover.CoverMetaRepository
import io.github.sejoung.panelyink.library.data.CoverPruner.clearAll
import io.github.sejoung.panelyink.library.data.CoverPruner.prune
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 표지 캐시 디스크 정리.
 *
 * 두 가지 정리 모드:
 * - [prune]: 자동 + 사용자 명시. orphan(메타 없는 파일) 정리 + LRU(사이즈 초과 시
 *   가장 오래된 메타+파일부터 삭제)
 * - [clearAll]: 사용자 명시 "표지 캐시 비우기". 디스크 파일 + 메타(FAILED 포함) 모두
 *   삭제 → 다음 라이브러리 진입에서 모든 책 표지 재추출. FAILED 메타가 같이 삭제되니
 *   깨진 책도 재시도됨.
 *
 * 호출 시점:
 * - [io.github.sejoung.panelyink.PanelyInkApp.onCreate] 백그라운드: [prune]
 * - 앱 설정 화면 "표지 캐시 비우기" 버튼: [clearAll]
 */
object CoverPruner {

  /** 표지 디스크 캐시 상한(바이트). 표지 1장 ~50KB이면 1600장 가능. */
  const val DEFAULT_MAX_BYTES: Long = 80L * 1024 * 1024 // 80MB

  private const val DIR = "covers"
  private const val EXT = "png"
  private const val TAG = "PanelyInk.CoverPruner"

  /**
   * 자동/명시 LRU 정리. orphan + 사이즈 LRU 둘 다.
   *
   * 1. 디스크 파일 list + 메타 OK 목록 조회
   * 2. orphan(파일 nameWithoutExtension이 메타 셋에 없음) 삭제
   * 3. 남은 파일 사이즈 합 ≤ maxBytes면 종료
   * 4. 초과면 메타 LRU 순(가장 오래된 것 먼저)으로 메타+파일 삭제
   *
   * @return 삭제된 파일 수 (orphan + LRU 합계).
   */
  suspend fun prune(
    context: Context,
    coverMetaRepo: CoverMetaRepository,
    maxBytes: Long = DEFAULT_MAX_BYTES,
  ): Int = withContext(Dispatchers.IO) {
    val dir = File(context.filesDir, DIR)
    if (!dir.exists()) return@withContext 0

    val allFiles = dir.listFiles()?.toList().orEmpty()
    val metas = coverMetaRepo.loadOkOrderedByLru()
    val knownIds = metas.map { it.bookId }.toHashSet()

    var deleted = 0

    // 1단계: orphan(메타 없는 디스크 파일) 정리
    for (file in allFiles) {
      if (file.nameWithoutExtension !in knownIds) {
        if (file.delete()) deleted++
      }
    }
    val orphansDeleted = deleted

    // 2단계: 남은 파일의 사이즈 합. 한도 미만이면 종료.
    val remaining = allFiles.filter {
      it.exists() && it.nameWithoutExtension in knownIds
    }
    var total = remaining.sumOf { it.length() }
    if (total <= maxBytes) {
      Log.d(
        TAG,
        "prune: orphans=$orphansDeleted total=${total / 1024}KB " +
          "max=${maxBytes / 1024}KB (under limit)",
      )
      return@withContext deleted
    }

    // 3단계: LRU 순 (오래된 것 먼저) 메타+파일 삭제 — 한도 미만 될 때까지
    for (meta in metas) {
      if (total <= maxBytes) break
      val file = File(dir, "${meta.bookId}.$EXT")
      val size = if (file.exists()) file.length() else 0L
      if (file.exists() && file.delete()) {
        deleted++
        total -= size
      }
      coverMetaRepo.delete(meta.bookId)
    }

    Log.d(
      TAG,
      "prune: orphans=$orphansDeleted lru=${deleted - orphansDeleted} " +
        "total=${total / 1024}KB",
    )
    deleted
  }

  /**
   * 사용자 명시 "표지 캐시 비우기" — 디스크 파일 + 메타(FAILED 포함) 모두 삭제.
   * 다음 라이브러리 진입에서 모든 표지 재추출.
   *
   * @return 삭제된 파일 수.
   */
  suspend fun clearAll(
    context: Context,
    coverMetaRepo: CoverMetaRepository,
  ): Int = withContext(Dispatchers.IO) {
    val dir = File(context.filesDir, DIR)
    var deleted = 0
    if (dir.exists()) {
      dir.listFiles()?.forEach { file ->
        if (file.delete()) deleted++
      }
    }
    coverMetaRepo.deleteAll()
    Log.d(TAG, "clearAll: deleted=$deleted files + all meta")
    deleted
  }
}
