package io.github.sejoung.panelyink.data.db.position

import io.github.sejoung.panelyink.core.position.BookProgress
import io.github.sejoung.panelyink.core.position.PositionRepository
import io.github.sejoung.panelyink.data.db.PanelyDatabase
import io.github.sejoung.panelyink.data.db.flatMapInChunks
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

    override suspend fun load(bookId: String): BookProgress? = withContext(Dispatchers.IO) {
        dao.load(bookId)?.let { BookProgress(pageIndex = it.pageIndex, pageCount = it.pageCount) }
    }

    override suspend fun save(bookId: String, pageIndex: Int, pageCount: Int) =
        withContext(Dispatchers.IO) {
            dao.upsert(
                PositionEntity(
                    bookId = bookId,
                    pageIndex = pageIndex,
                    pageCount = pageCount,
                    updatedAt = clock(),
                ),
            )
        }

    override suspend fun loadUpdatedAtMap(bookIds: List<String>): Map<String, Long> {
        if (bookIds.isEmpty()) return emptyMap()
        return withContext(Dispatchers.IO) {
            // 1000+ 책 라이브러리에서 SQLite host param 한도(999) 회피.
            bookIds.flatMapInChunks { chunk -> dao.loadUpdatedAtFor(chunk) }
                .associate { it.bookId to it.updatedAt }
        }
    }

    override suspend fun loadProgressMap(bookIds: List<String>): Map<String, BookProgress> {
        if (bookIds.isEmpty()) return emptyMap()
        return withContext(Dispatchers.IO) {
            bookIds.flatMapInChunks { chunk -> dao.loadAllByIds(chunk) }
                .associate { it.bookId to BookProgress(it.pageIndex, it.pageCount) }
        }
    }
}
