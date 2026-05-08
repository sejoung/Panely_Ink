package io.github.sejoung.panelyink.data.db

import io.github.sejoung.panelyink.reader.BookSettings
import io.github.sejoung.panelyink.reader.deserializeDirection
import io.github.sejoung.panelyink.reader.deserializeFitMode
import io.github.sejoung.panelyink.reader.serializeFitMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 책별 [BookSettings] 영속 저장. 페이지 위치([io.github.sejoung.panelyink.core.position.PositionRepository])와
 * 분리해 유지 — 위치는 매 페이지 갱신, 설정은 사용자 명시 변경 시만.
 *
 * 책 첫 진입 시 [load] 결과가 null이면 [BookSettings.DEFAULTS] 사용. 사용자가 메뉴에서
 * 한 가지라도 변경하면 [save] 호출.
 */
interface BookSettingsRepository {
    suspend fun load(bookId: String): BookSettings?
    suspend fun save(bookId: String, settings: BookSettings)
}

class RoomBookSettingsRepository(
    private val db: PanelyDatabase,
    private val clock: () -> Long = System::currentTimeMillis,
) : BookSettingsRepository {

    private val dao get() = db.bookSettingsDao()

    override suspend fun load(bookId: String): BookSettings? = withContext(Dispatchers.IO) {
        dao.load(bookId)?.toDomain()
    }

    override suspend fun save(bookId: String, settings: BookSettings) =
        withContext(Dispatchers.IO) {
            dao.upsert(settings.toEntity(bookId, clock()))
        }
}

/** Entity → 도메인. 깨진 enum/sealed 값은 deserializer가 안전 폴백. */
internal fun BookSettingsEntity.toDomain(): BookSettings = BookSettings(
    fitMode = deserializeFitMode(fitMode),
    direction = deserializeDirection(direction),
    trimEnabled = trimEnabled,
    contrast = contrast,
    invertEnabled = invertEnabled,
    fullRefreshInterval = fullRefreshInterval,
)

internal fun BookSettings.toEntity(bookId: String, updatedAt: Long): BookSettingsEntity =
    BookSettingsEntity(
        bookId = bookId,
        fitMode = serializeFitMode(fitMode),
        direction = direction.name,
        trimEnabled = trimEnabled,
        contrast = contrast,
        invertEnabled = invertEnabled,
        fullRefreshInterval = fullRefreshInterval,
        updatedAt = updatedAt,
    )
