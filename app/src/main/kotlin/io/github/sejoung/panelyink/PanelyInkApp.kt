package io.github.sejoung.panelyink

import android.app.Application
import android.util.Log
import io.github.sejoung.panelyink.data.db.PanelyDatabase
import io.github.sejoung.panelyink.data.db.PositionMigration
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
            runCatching {
                val db = PanelyDatabase.getInstance(this@PanelyInkApp)
                PositionMigration.migrateFromPrefs(this@PanelyInkApp, db)
            }.onFailure {
                Log.w(TAG, "position migration failed", it)
            }
        }
    }

    companion object {
        private const val TAG = "PanelyInk.App"
    }
}
