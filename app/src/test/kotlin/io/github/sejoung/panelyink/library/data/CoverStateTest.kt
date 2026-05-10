package io.github.sejoung.panelyink.library.data

import io.github.sejoung.panelyink.data.db.cover.CoverStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `CoverState` 핵심 invariant 회귀 방지.
 *
 * **배경**: Grid 모드에서 화면에 보이는 책 수가 cover memory cache 한도(40여 개)를 초과하면
 * LRU evict가 발생한다. 이전에 `state.covers`를 `coverMemoryCache.toMap()` 스냅샷으로
 * reassign하던 코드는 evict 시 시각 항목의 cover를 null로 토글했고, `LaunchedEffect`가
 * 그 토글에 재발화하면서 무한 reload 루프를 만들었다.
 *
 * 이 테스트는 다음을 잠근다:
 * 1. `mergeCovers` / `mergeCoverStatuses`는 추가만 한다 — **절대 기존 키를 떨어뜨리지 않는다**
 * 2. `pruneCoversToVisible`은 명시적 폴더 이동 경로에서만 동작한다
 *
 * `android.net.Uri`에 의존하는 [CoverState.visibleBookIdsFor] 테스트는 androidTest 트랙.
 * 여기서는 순수 Map 연산만 검증.
 */
class CoverStateTest {

    // ---- mergeCovers: additive invariant ----

    @Test
    fun mergeCovers_emptyIncomingReturnsSameInstance() {
        val current = mapOf("a" to 1, "b" to 2)
        val merged = CoverState.mergeCovers(current, emptyMap())
        // 동일 인스턴스를 반환해 불필요한 recompose / state 비교 비용을 피해야 한다.
        assertSame(current, merged)
    }

    @Test
    fun mergeCovers_addsNewKeys() {
        val current = mapOf("a" to 1)
        val merged = CoverState.mergeCovers(current, mapOf("b" to 2))
        assertEquals(mapOf("a" to 1, "b" to 2), merged)
    }

    @Test
    fun mergeCovers_overwritesExistingKey() {
        // 같은 책 표지를 재추출한 결과가 들어올 수 있다 — 새 값을 우선.
        val merged = CoverState.mergeCovers(mapOf("a" to 1), mapOf("a" to 99))
        assertEquals(mapOf("a" to 99), merged)
    }

    @Test
    fun mergeCovers_neverDropsExistingKeysWhenAddingMany() {
        // 회귀 방어선: 1000개 추가해도 기존 키는 무조건 살아남는다 — Grid LRU evict-loop 방어.
        val current = (0 until 100).associate { "old$it" to it }
        val incoming = (0 until 1000).associate { "new$it" to it }
        val merged = CoverState.mergeCovers(current, incoming)
        for ((key, value) in current) {
            assertEquals(
                "기존 키 $key 가 사라짐 — additive invariant 위반",
                value,
                merged[key],
            )
        }
        assertEquals(current.size + incoming.size, merged.size)
    }

    @Test
    fun mergeCovers_repeatedAddsAccumulate() {
        // 같은 폴더에서 표지가 하나씩 도착하는 시나리오 — 모두 누적되어야 한다.
        var state = emptyMap<String, Int>()
        for (i in 0 until 50) {
            state = CoverState.mergeCovers(state, mapOf("book$i" to i))
        }
        assertEquals(50, state.size)
        for (i in 0 until 50) {
            assertEquals(i, state["book$i"])
        }
    }

    // ---- mergeCoverStatuses: 동일 정책 ----

    @Test
    fun mergeCoverStatuses_emptyIncomingReturnsSameInstance() {
        val current = mapOf("a" to CoverStatus.OK)
        assertSame(current, CoverState.mergeCoverStatuses(current, emptyMap()))
    }

    @Test
    fun mergeCoverStatuses_overwritesFailedToOk() {
        // 사용자가 캐시 비우고 재추출 시 FAILED → OK 전이 가능해야.
        val merged = CoverState.mergeCoverStatuses(
            current = mapOf("a" to CoverStatus.FAILED),
            incoming = mapOf("a" to CoverStatus.OK),
        )
        assertEquals(CoverStatus.OK, merged["a"])
    }

    @Test
    fun mergeCoverStatuses_keepsExistingWhenAddingNew() {
        val merged = CoverState.mergeCoverStatuses(
            current = mapOf("a" to CoverStatus.OK, "b" to CoverStatus.FAILED),
            incoming = mapOf("c" to CoverStatus.OK),
        )
        assertEquals(CoverStatus.OK, merged["a"])
        assertEquals(CoverStatus.FAILED, merged["b"])
        assertEquals(CoverStatus.OK, merged["c"])
    }

    // ---- pruneCoversToVisible: 폴더 이동 시점 정리 ----

    @Test
    fun pruneCoversToVisible_emptyCurrentReturnsSameInstance() {
        val current = emptyMap<String, Int>()
        assertSame(current, CoverState.pruneCoversToVisible(current, setOf("a")))
    }

    @Test
    fun pruneCoversToVisible_emptyVisibleReturnsEmpty() {
        // visible이 비어있으면 cover state도 비워야 한다 — 폴더가 비어있는 상태.
        val pruned = CoverState.pruneCoversToVisible(
            current = mapOf("a" to 1, "b" to 2),
            visibleBookIds = emptySet(),
        )
        assertTrue(pruned.isEmpty())
    }

    @Test
    fun pruneCoversToVisible_keepsOnlyVisibleKeys() {
        val pruned = CoverState.pruneCoversToVisible(
            current = mapOf("a" to 1, "b" to 2, "c" to 3),
            visibleBookIds = setOf("b"),
        )
        assertEquals(mapOf("b" to 2), pruned)
    }

    @Test
    fun pruneCoversToVisible_allVisibleKeepsAll() {
        val current = mapOf("a" to 1, "b" to 2)
        val pruned = CoverState.pruneCoversToVisible(current, setOf("a", "b"))
        assertEquals(current, pruned)
    }

    @Test
    fun pruneCoversToVisible_disjointVisibleDropsAll() {
        // 사용자가 다른 폴더로 이동한 시나리오 — 이전 폴더 책 ID가 새 visible에 없음.
        val pruned = CoverState.pruneCoversToVisible(
            current = mapOf("a" to 1, "b" to 2),
            visibleBookIds = setOf("c", "d"),
        )
        assertTrue(pruned.isEmpty())
    }
}
