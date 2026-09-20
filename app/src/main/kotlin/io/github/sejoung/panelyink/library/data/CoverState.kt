package io.github.sejoung.panelyink.library.data

import io.github.sejoung.panelyink.data.db.cover.CoverStatus
import io.github.sejoung.panelyink.library.model.BookEntry
import io.github.sejoung.panelyink.library.model.FolderEntry
import io.github.sejoung.panelyink.library.model.LibraryEntry
import io.github.sejoung.panelyink.library.model.bookId

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
 * 메모리 bound는 [pruneCoversToVisible]이 폴더 이동 시점에 처리하고, 한 폴더에 책이 수백~
 * 수천 권인 경우는 [trimToMax]가 **삽입 순서 기준 가장 오래된 것부터** 잘라 상한을 둔다.
 * row effect 키에 `cover == null`이 없으므로 trim이 재요청 루프를 만들지 않는다 — 잘린
 * 항목은 그 행이 다시 composition에 들어올 때 `requestCover`가 memory/disk 캐시에서 복원.
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

    /**
     * covers가 [max]개를 넘으면 삽입 순서상 앞(가장 오래 전에 추가된 것)부터 버린다.
     * `Map.plus`/`filterKeys` 결과는 LinkedHashMap이라 삽입 순서가 유지된다. [max]는 한 화면에
     * 보이는 행 수보다 충분히 커야 한다 — 방금 추가된(=화면에 보이는) 항목은 항상 남는다.
     */
    fun <T> trimToMax(current: Map<String, T>, max: Int): Map<String, T> {
        if (current.size <= max) return current
        val drop = current.size - max
        val trimmed = LinkedHashMap<String, T>(max)
        var index = 0
        for ((key, value) in current) {
            if (index++ >= drop) trimmed[key] = value
        }
        return trimmed
    }

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
                is BookEntry -> ids += entry.bookId.value
                is FolderEntry -> {
                    folderFirstBook[entry.documentUri]?.let { ids += it }
                    entry.nestedBooks?.firstOrNull()?.let {
                        ids += it.bookId.value
                    }
                }
            }
        }
        return ids
    }
}
