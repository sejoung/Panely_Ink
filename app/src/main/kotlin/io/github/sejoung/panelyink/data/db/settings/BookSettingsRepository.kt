package io.github.sejoung.panelyink.data.db.settings

import io.github.sejoung.panelyink.core.preferences.deserializeOrientation
import io.github.sejoung.panelyink.data.db.PanelyDatabase
import io.github.sejoung.panelyink.reader.model.BookSettingsOverrides
import io.github.sejoung.panelyink.reader.model.deserializeDirection
import io.github.sejoung.panelyink.reader.model.deserializeFitMode
import io.github.sejoung.panelyink.reader.model.serializeFitMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 책별 [BookSettingsOverrides] 영속 저장. 페이지 위치([io.github.sejoung.panelyink.core.position.PositionRepository])와
 * 분리해 유지 — 위치는 매 페이지 갱신, override는 사용자 명시 변경 시만.
 *
 * **저장 정책**: 빈 override([BookSettingsOverrides.isEmpty])는 row 삭제. 그래야 다음 진입에 [io.github.sejoung.panelyink.core.preferences.AppPreferences]의
 * 최신 값이 그대로 적용된다. 한 필드라도 non-null이면 upsert.
 *
 * 책 첫 진입 시 [load]가 null/빈 override면 진입 측에서 [BookSettingsOverrides.NONE].resolve(appPrefs)로 합성.
 */
interface BookSettingsRepository {
    suspend fun load(bookId: String): BookSettingsOverrides?
    suspend fun save(bookId: String, overrides: BookSettingsOverrides)
}

class RoomBookSettingsRepository(
    private val db: PanelyDatabase,
    private val clock: () -> Long = System::currentTimeMillis,
) : BookSettingsRepository {

    private val dao get() = db.bookSettingsDao()

    override suspend fun load(bookId: String): BookSettingsOverrides? = withContext(Dispatchers.IO) {
        dao.load(bookId)?.toDomain()
    }

    override suspend fun save(bookId: String, overrides: BookSettingsOverrides) =
        withContext(Dispatchers.IO) {
            if (overrides.isEmpty) {
                // 모든 필드가 null이면 row 자체를 비워야 다음 진입에 전역값 그대로 들어옴.
                dao.delete(bookId)
            } else {
                dao.upsert(overrides.toEntity(bookId, clock()))
            }
        }
}

/** Entity → 도메인. 깨진 enum/sealed 값은 deserializer가 안전 폴백. NULL은 그대로 NULL. */
internal fun BookSettingsEntity.toDomain(): BookSettingsOverrides = BookSettingsOverrides(
    fitMode = fitMode?.let(::deserializeFitMode),
    direction = direction?.let(::deserializeDirection),
    trimEnabled = trimEnabled,
    contrast = contrast,
    spreadMode = spreadMode,
    coverAlone = coverAlone,
    orientation = orientation?.let(::deserializeOrientation),
)

internal fun BookSettingsOverrides.toEntity(bookId: String, updatedAt: Long): BookSettingsEntity =
    BookSettingsEntity(
        bookId = bookId,
        fitMode = fitMode?.let(::serializeFitMode),
        direction = direction?.name,
        trimEnabled = trimEnabled,
        contrast = contrast,
        spreadMode = spreadMode,
        coverAlone = coverAlone,
        orientation = orientation?.name,
        updatedAt = updatedAt,
    )
