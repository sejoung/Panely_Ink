package io.github.sejoung.panelyink.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.content.edit
import io.github.sejoung.panelyink.data.db.PanelyDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 앱이 보유한 모든 영속 데이터를 비우고 신규 사용자 상태로 되돌린다.
 *
 * 정리 대상:
 * 1. SAF 영속 권한 — `panely_ink_prefs.library_root_uris`에 등록된 모든 root에 대해
 *    `releasePersistableUriPermission`. 이 단계가 prefs 비우기보다 **먼저** 실행되어야
 *    URI 목록을 잃지 않음.
 * 2. SharedPreferences — `panely_ink_prefs`(라이브러리), `panely_ink_app_prefs`(전역),
 *    `panely_ink_positions`(v0.x 호환 잔여) 모두 clear.
 * 3. Room DB — `clearAllTables()`로 position/book_settings/bookmark/cover_meta 모든 행 삭제.
 *    스키마는 보존(테이블 자체는 유지).
 * 4. 디스크 캐시 — `filesDir/covers` 디렉토리 재귀 삭제.
 *
 * 호출 후 호출자(LibraryViewModel)는 state를 [io.github.sejoung.panelyink.library.LibraryState]
 * 기본값으로 갱신해야 — 이 클래스는 state 자체를 건드리지 않음.
 */
object AppDataResetter {

    private const val TAG = "PanelyInk.Reset"

    private const val PREFS_LIBRARY = "panely_ink_prefs"
    private const val PREFS_APP = "panely_ink_app_prefs"
    private const val PREFS_LEGACY_POSITIONS = "panely_ink_positions"
    private const val KEY_ROOTS = "library_root_uris"
    private const val COVERS_DIR = "covers"

    suspend fun resetAll(context: Context, db: PanelyDatabase) = withContext(Dispatchers.IO) {
        val app = context.applicationContext

        // 1. SAF 권한 release — prefs 비우기 전에 root 목록 읽기
        runCatching {
            val libraryPrefs = app.getSharedPreferences(PREFS_LIBRARY, Context.MODE_PRIVATE)
            val saved = libraryPrefs.getStringSet(KEY_ROOTS, emptySet()).orEmpty()
            saved.forEach { uriString ->
                runCatching {
                    app.contentResolver.releasePersistableUriPermission(
                        Uri.parse(uriString),
                        Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    )
                }.onFailure {
                    Log.w(TAG, "release permission failed for $uriString", it)
                }
            }
        }.onFailure { Log.w(TAG, "SAF release stage failed", it) }

        // 2. SharedPreferences 비우기
        listOf(PREFS_LIBRARY, PREFS_APP, PREFS_LEGACY_POSITIONS).forEach { name ->
            runCatching {
                app.getSharedPreferences(name, Context.MODE_PRIVATE).edit { clear() }
            }.onFailure { Log.w(TAG, "clear prefs $name failed", it) }
        }

        // 3. Room 모든 테이블 비우기 (스키마 보존)
        runCatching { db.clearAllTables() }
            .onFailure { Log.w(TAG, "clearAllTables failed", it) }

        // 4. 디스크 캐시 정리
        runCatching {
            File(app.filesDir, COVERS_DIR).deleteRecursively()
        }.onFailure { Log.w(TAG, "covers cleanup failed", it) }

        Log.d(TAG, "resetAll done")
    }
}
