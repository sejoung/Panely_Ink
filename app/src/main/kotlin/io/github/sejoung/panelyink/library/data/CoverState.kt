package io.github.sejoung.panelyink.library.data

import io.github.sejoung.panelyink.core.book.BookIdentity
import io.github.sejoung.panelyink.data.db.cover.CoverStatus
import io.github.sejoung.panelyink.library.model.BookEntry
import io.github.sejoung.panelyink.library.model.FolderEntry
import io.github.sejoung.panelyink.library.model.LibraryEntry

/**
 * `LibraryState.covers` / `coverStatus` 갱신 정책.
 *
 * **불변량 (회귀 방어선)**: 표지 추가는 **항상 additive**. 한 번 로드된 표지는 사용자가
 * 폴더를 이동(`pruneCoversToVisible`)하거나 명시적 `clearCoverCache`를 호출하기 전까지
 * `state.covers`에서 사라지지 않는다.
 *
 * 왜 이게 중요한가: `BookGridCell`/`BookRow` 등의 `LaunchedEffect(entry.documentUri)`가
 * Compose 라이프사이클을 그대로 쓰기 위해 idempotent하게 `requestCover`를 부르는데,
 * 만약 `state.covers`가 LRU cache snapshot으로 reassign되어 evict가 키를 떨어뜨리면
 * 시각 항목의 cover가 null로 토글되며 무한 reload 루프가 발생한다.
 *
 * 메모리 bound는 LRU가 아니라 [pruneCoversToVisible]이 폴더 이동 시점에 처리.
 */
internal object CoverState {

    /**
     * 기존 covers에 새 항목을 합친다 — `incoming`이 비어 있으면 기존 인스턴스를 그대로
     * 반환해 불필요한 allocation/recompose를 피한다. **반드시 additive**: cache snapshot
     * 으로 대체하는 패턴은 회귀.
     */
    fun <T> mergeCovers(
        current: Map<String, T>,
        incoming: Map<String, T>,
    ): Map<String, T> = if (incoming.isEmpty()) current else current + incoming

    /** [mergeCovers]와 동일 정책의 status 변형. */
    fun mergeCoverStatuses(
        current: Map<String, CoverStatus>,
        incoming: Map<String, CoverStatus>,
    ): Map<String, CoverStatus> = if (incoming.isEmpty()) current else current + incoming

    /**
     * 폴더 이동 시 호출 — 새 화면에 보일 책(직접 책 + 폴더 첫 책 + 가상 폴더 첫 nested 책)의
     * bookId 집합 이외 키를 모두 drop. 화면 밖 항목의 ImageBitmap이 GC 가능해진다.
     *
     * 기존 cache(in-memory `coverMemoryCache`)는 별도 — 같은 폴더 재진입 시 디스크 skip
     * fast path로 즉시 복원.
     */
    fun <T> pruneCoversToVisible(
        current: Map<String, T>,
        visibleBookIds: Set<String>,
    ): Map<String, T> = when {
        current.isEmpty() -> current
        visibleBookIds.isEmpty() -> emptyMap()
        else -> current.filterKeys { it in visibleBookIds }
    }

    /**
     * 화면 entries로부터 `pruneCoversToVisible`이 사용할 visible bookId 집합을 만든다.
     *
     * - 직접 [BookEntry]: 자신의 bookId
     * - [FolderEntry]: `folderFirstBook[uri]` 매핑된 첫 책 + 가상 폴더의 nestedBooks 첫 책
     *
     * 폴더 안 모든 책을 다 보존하지는 않는다 — 폴더 자체는 표지 1장만 보여주므로.
     */
    fun visibleBookIdsFor(
        entries: List<LibraryEntry>,
        folderFirstBook: Map<android.net.Uri, String>,
    ): Set<String> {
        if (entries.isEmpty()) return emptySet()
        val ids = mutableSetOf<String>()
        for (entry in entries) {
            when (entry) {
                is BookEntry -> ids += BookIdentity.fromEntry(entry).value
                is FolderEntry -> {
                    folderFirstBook[entry.documentUri]?.let { ids += it }
                    entry.nestedBooks?.firstOrNull()?.let {
                        ids += BookIdentity.fromEntry(it).value
                    }
                }
            }
        }
        return ids
    }
}
