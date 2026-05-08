package io.github.sejoung.panelyink.data.db

import io.github.sejoung.panelyink.core.position.PositionKey
import io.github.sejoung.panelyink.core.position.PositionRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * [PositionRepository]의 Room 기반 구현 — M3에서 SharedPreferences를 대체.
 *
 * SQLite I/O는 메인스레드 차단 위험이 있어 모든 호출을 [Dispatchers.IO]로 강제한다.
 * Room의 KSP 생성 DAO도 suspend 호출에 한해 자동으로 IO 디스패처에서 실행하지만,
 * 명시적 `withContext`로 추가 안전 장치.
 *
 * 시계 의존성([clock])은 테스트 가능성 위해 주입 — 실제 사용 시 기본값(System.currentTimeMillis)
 * 그대로 두면 됨.
 */
class RoomPositionRepository(
    private val db: PanelyDatabase,
    private val clock: () -> Long = System::currentTimeMillis,
) : PositionRepository {

    private val dao get() = db.positionDao()

    override suspend fun load(bookId: String): Int? = withContext(Dispatchers.IO) {
        dao.load(bookId)?.pageIndex
    }

    override suspend fun save(key: PositionKey) = withContext(Dispatchers.IO) {
        dao.upsert(
            PositionEntity(
                bookId = key.bookId,
                pageIndex = key.pageIndex,
                updatedAt = clock(),
            ),
        )
    }
}
