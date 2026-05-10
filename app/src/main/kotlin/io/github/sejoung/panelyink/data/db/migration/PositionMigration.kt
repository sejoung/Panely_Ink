package io.github.sejoung.panelyink.data.db.migration

import android.content.Context
import androidx.core.content.edit
import io.github.sejoung.panelyink.data.db.PanelyDatabase
import io.github.sejoung.panelyink.data.db.position.PositionEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * v0.x SharedPreferences("panely_ink_positions") → Room `position` 테이블 1회 이전.
 *
 * - 멱등(idempotent): 마이그레이션 완료 플래그를 앱 prefs에 기록해 재실행에서 no-op
 * - 파괴적: 마이그레이션 성공 후 원본 파일을 통째 삭제(deleteSharedPreferences) — Room이
 *   이후 모든 진실의 source. 다운그레이드 시나리오는 v1.0 출시 후엔 의미 없으며, 박제된
 *   prefs 파일이 모든 cold start에서 메모리에 로드되는 비용을 회피.
 * - 메인스레드 차단 X: 호출자가 백그라운드 코루틴에서 호출
 *
 * 플래그를 v0.x 파일 자신이 아닌 [APP_PREFS_NAME]에 두는 이유: v0.x 파일을 지우려면
 * 그 파일에 의존하지 않는 곳에 "이미 했음" 마커가 있어야 한다.
 */
object PositionMigration {

    private const val LEGACY_PREFS_NAME = "panely_ink_positions"
    private const val APP_PREFS_NAME = "panely_ink_app_prefs"
    private const val KEY_MIGRATED = "position_migrated_to_room"

    suspend fun migrateFromPrefs(context: Context, db: PanelyDatabase) =
        withContext(Dispatchers.IO) {
            val app = context.applicationContext
            val appPrefs = app.getSharedPreferences(APP_PREFS_NAME, Context.MODE_PRIVATE)
            if (appPrefs.getBoolean(KEY_MIGRATED, false)) return@withContext

            // 신규 사용자(파일 없음)는 getSharedPreferences가 빈 파일을 만드는 부수효과를
            // 피하기 위해 디스크 직접 확인. shared_prefs/<name>.xml 경로는 안드로이드 표준.
            val legacyFile = File(
                File(app.applicationInfo.dataDir, "shared_prefs"),
                "$LEGACY_PREFS_NAME.xml",
            )
            if (legacyFile.exists()) {
                val legacyPrefs = app.getSharedPreferences(
                    LEGACY_PREFS_NAME,
                    Context.MODE_PRIVATE,
                )
                val now = System.currentTimeMillis()
                val entries = legacyPrefs.all.mapNotNull { (key, value) ->
                    val page = (value as? Int) ?: return@mapNotNull null
                    // pageCount=0(unknown) — 사용자가 책을 다시 열면 ReaderScreen이 갱신.
                    PositionEntity(
                        bookId = key,
                        pageIndex = page,
                        pageCount = 0,
                        updatedAt = now,
                    )
                }
                if (entries.isNotEmpty()) {
                    db.positionDao().insertAll(entries)
                }
            }

            // 플래그를 먼저 set한 뒤 파일 삭제 — 둘 사이에서 process kill되면 다음 실행에서
            // 플래그=true / 파일=존재 상태가 되지만, deleteSharedPreferences는 idempotent이고
            // 다음 실행에서 if 분기 자체가 early return이라 다시 시도되지 않는다. 박제 1회 잔존
            // 가능성은 있지만 결과적으로 데이터 정합성에는 영향 없음(Room이 source).
            appPrefs.edit { putBoolean(KEY_MIGRATED, true) }
            app.deleteSharedPreferences(LEGACY_PREFS_NAME)
        }
}
