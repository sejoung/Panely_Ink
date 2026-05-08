package io.github.sejoung.panelyink

import android.app.Application
import android.util.Log
import io.github.sejoung.panelyink.data.db.PanelyDatabase
import io.github.sejoung.panelyink.data.db.PositionMigration
import io.github.sejoung.panelyink.data.db.RoomCoverMetaRepository
import io.github.sejoung.panelyink.library.CoverPruner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 앱 프로세스 진입점. Room DB 인스턴스 캐싱과 1회 마이그레이션을 담당.
 *
 * Android Application은 Activity보다 일찍 만들어지므로, 첫 [io.github.sejoung.panelyink.MainActivity]
 * 진입 전에 SharedPreferences → Room 데이터 이전이 시작된다. 마이그레이션이 끝나기 전
 * 사용자가 책을 열어도 신규 사용자처럼 빈 결과가 한 번 나올 수 있는데, 페이지 이동
 * 시점에 즉시 새 데이터가 Room에 누적되므로 사용자 데이터 손실은 없다.
 */
class PanelyInkApp : Application() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        appScope.launch {
            val db = PanelyDatabase.getInstance(this@PanelyInkApp)
            runCatching {
                PositionMigration.migrateFromPrefs(this@PanelyInkApp, db)
            }.onFailure {
                Log.w(TAG, "position migration failed", it)
            }
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
