package io.github.sejoung.panelyink.data.db

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 책별 표지 추출 메타 ([CoverMetaEntity]) 영속.
 *
 * 호출자([io.github.sejoung.panelyink.library.LibraryViewModel])는:
 * 1. [load]로 status 확인 → FAILED면 재시도 skip
 * 2. 디스크 캐시 hit → 그대로 사용
 * 3. miss/손상 → 추출 시도 → 결과(OK/FAILED)를 [save]로 기록
 */
interface CoverMetaRepository {
    suspend fun load(bookId: String): CoverMeta?

    suspend fun save(
        bookId: String,
        status: CoverStatus,
        sourcePageIndex: Int = 0,
    )

    /** 사용자 명시 새로고침 시 메타 삭제 → 재추출 가능. */
    suspend fun delete(bookId: String)

    /** OK 상태 메타를 추출 시점 오름차순(가장 오래된 것 먼저)으로. LRU 정리에 사용. */
    suspend fun loadOkOrderedByLru(): List<CoverMeta>

    /** 사용자 명시 "표지 캐시 비우기" — FAILED 메타도 함께 삭제. */
    suspend fun deleteAll()
}

/** 도메인 — Entity의 status String을 enum으로 변환. */
data class CoverMeta(
    val bookId: String,
    val status: CoverStatus,
    val sourcePageIndex: Int,
    val extractedAt: Long,
)

class RoomCoverMetaRepository(
    private val db: PanelyDatabase,
    private val clock: () -> Long = System::currentTimeMillis,
) : CoverMetaRepository {

    private val dao get() = db.coverMetaDao()

    override suspend fun load(bookId: String): CoverMeta? = withContext(Dispatchers.IO) {
        dao.load(bookId)?.toDomain()
    }

    override suspend fun save(
        bookId: String,
        status: CoverStatus,
        sourcePageIndex: Int,
    ) = withContext(Dispatchers.IO) {
        dao.upsert(
            CoverMetaEntity(
                bookId = bookId,
                status = status.name,
                sourcePageIndex = sourcePageIndex,
                extractedAt = clock(),
            ),
        )
    }

    override suspend fun delete(bookId: String) = withContext(Dispatchers.IO) {
        dao.delete(bookId)
    }

    override suspend fun loadOkOrderedByLru(): List<CoverMeta> = withContext(Dispatchers.IO) {
        dao.loadOkOrderedByLru().map { it.toDomain() }
    }

    override suspend fun deleteAll() = withContext(Dispatchers.IO) {
        dao.deleteAll()
    }
}

/** Entity → 도메인. 알 수 없는 status는 FAILED로 안전 폴백(재시도 차단 우선). */
private fun CoverMetaEntity.toDomain(): CoverMeta = CoverMeta(
    bookId = bookId,
    status = runCatching { CoverStatus.valueOf(status) }.getOrDefault(CoverStatus.FAILED),
    sourcePageIndex = sourcePageIndex,
    extractedAt = extractedAt,
)
