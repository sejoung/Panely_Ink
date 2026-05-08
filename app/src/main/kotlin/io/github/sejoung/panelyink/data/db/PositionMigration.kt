package io.github.sejoung.panelyink.data.db

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * v0.x SharedPreferences("panely_ink_positions") → Room `position` 테이블 1회 이전.
 *
 * - 멱등(idempotent): 마이그레이션 완료 플래그를 같은 prefs에 기록해 재실행에서 no-op
 * - 비파괴: 원본 prefs 키들은 보존 — 사용자가 v1.0 출시 후 v0.x로 다운그레이드해도
 *   기존 데이터로 동작. 디스크 누적은 무시할 수준(키당 ~50바이트)
 * - 메인스레드 차단 X: 호출자가 백그라운드 코루틴에서 호출
 */
object PositionMigration {

    private const val PREFS_NAME = "panely_ink_positions"
    private const val KEY_MIGRATED = "_migrated_to_room"

    suspend fun migrateFromPrefs(context: Context, db: PanelyDatabase) =
        withContext(Dispatchers.IO) {
            val prefs = context.applicationContext
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            if (prefs.getBoolean(KEY_MIGRATED, false)) return@withContext

            val now = System.currentTimeMillis()
            val entries = prefs.all.mapNotNull { (key, value) ->
                if (key == KEY_MIGRATED) return@mapNotNull null
                val page = (value as? Int) ?: return@mapNotNull null
                PositionEntity(bookId = key, pageIndex = page, updatedAt = now)
            }
            if (entries.isNotEmpty()) {
                db.positionDao().insertAll(entries)
            }
            prefs.edit { putBoolean(KEY_MIGRATED, true) }
        }
}
