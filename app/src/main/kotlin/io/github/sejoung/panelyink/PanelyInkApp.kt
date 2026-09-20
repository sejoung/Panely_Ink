package io.github.sejoung.panelyink

import android.app.Application
import android.util.Log
import io.github.sejoung.panelyink.data.db.PanelyDatabase
import io.github.sejoung.panelyink.data.db.cover.RoomCoverMetaRepository
import io.github.sejoung.panelyink.data.db.migration.PositionMigration
import io.github.sejoung.panelyink.library.data.CoverPruner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 앱 프로세스 진입점. Room DB 인스턴스 캐싱과 1회 마이그레이션을 담당.
 *
 * Android Application은 Activity보다 일찍 만들어지므로, 첫 [io.github.sejoung.panelyink.MainActivity]
 * 진입 전에 SharedPreferences → Room 데이터 이전이 시작된다. 이전은 `OnConflictStrategy.IGNORE`로
 * 넣기 때문에, 끝나기 전에 리더가 위치를 읽으면(빈 결과 → 0쪽) 곧이어 저장되는 0쪽이 legacy 값을
 * 막아 그 책의 이어읽기 위치가 사라진다. 리더는 위치를 읽기 전에 [positionMigration]을 기다린다.
 */
class PanelyInkApp : Application() {

  private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

  /** v0.x 위치 이전 작업. 이미 이전된 설치에서는 플래그 확인만 하고 즉시 끝난다. */
  lateinit var positionMigration: Job
    private set

  override fun onCreate() {
    super.onCreate()
    positionMigration = appScope.launch {
      runCatching {
        val db = PanelyDatabase.getInstance(this@PanelyInkApp)
        PositionMigration.migrateFromPrefs(this@PanelyInkApp, db)
      }.onFailure {
        Log.w(TAG, "position migration failed", it)
      }
    }
    appScope.launch {
      positionMigration.join()
      val db = PanelyDatabase.getInstance(this@PanelyInkApp)
      // 표지 캐시 LRU 정리(M3) — 사용자 세션 시작 시 1회. 누적 사이즈가 한도
      // 미만이면 즉시 종료하므로 비용 작음.
      runCatching {
        val coverMetaRepo = RoomCoverMetaRepository(db)
        CoverPruner.prune(this@PanelyInkApp, coverMetaRepo)
      }.onFailure {
        Log.w(TAG, "cover prune failed", it)
      }
    }
  }

  companion object {
    private const val TAG = "PanelyInk.App"
  }
}
