package io.github.sejoung.panelyink.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * 앱 단일 Room 데이터베이스 — M3 라이브러리 보강 / 책별 설정 / 진행률 / 표지 캐시
 * 메타가 누적될 곳.
 *
 * v1: position 테이블만 (SharedPreferences에서 마이그레이션). 다음 단계에 BookSettings
 * / CoverMeta / Bookmark 테이블 추가 예정 — 그때 [version] 올리고 Migration 클래스 추가.
 *
 * 싱글톤 보장: [getInstance]가 더블 체크 락. 안드로이드 앱에서 DB는 프로세스당 1개.
 */
@Database(
    entities = [PositionEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class PanelyDatabase : RoomDatabase() {

    abstract fun positionDao(): PositionDao

    companion object {
        private const val DB_NAME = "panely_ink.db"

        @Volatile
        private var instance: PanelyDatabase? = null

        fun getInstance(context: Context): PanelyDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    PanelyDatabase::class.java,
                    DB_NAME,
                ).build().also { instance = it }
            }
    }
}
