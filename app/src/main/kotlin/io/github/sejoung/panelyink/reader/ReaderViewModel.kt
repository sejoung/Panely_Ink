package io.github.sejoung.panelyink.reader

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.sejoung.panelyink.core.fit.FitMode
import io.github.sejoung.panelyink.core.position.PositionKey
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 본문 리더 상태 머신. PRD §6.1 읽기 코어와 §6.1 e-ink 입력 정책의 공통 진입점.
 *
 * v1.0 책임:
 * - 현재 페이지 / 방향 / fit 모드 보유
 * - 프리로드 윈도(±3, PRD §6.1) 계산
 * - PositionKey 노출 — Resume / 북마크 저장의 1차 키
 * - 외부에서 주입된 [PageDecoder]에 프리로드 요청을 전달
 *
 * v1.0 비책임:
 * - 디코드 실제 호출 캐시 정책 (M1 페이지 캐시에서 다룸)
 * - 풀리프레시 주기 (Guidelines §10 — 뷰어 View 레이어가 결정)
 */
class ReaderViewModel(
    val bookId: String,
    val pageCount: Int,
    private val decoder: PageDecoder,
    initialPage: Int = 0,
    initialDirection: ReadingDirection = ReadingDirection.Ltr,
    initialFitMode: FitMode = FitMode.FitScreen,
) : ViewModel() {

    init {
        require(pageCount > 0) { "pageCount must be > 0" }
        require(initialPage in 0 until pageCount) { "initialPage out of range" }
    }

    private val _state = MutableStateFlow(
        ReaderState(
            currentPage = initialPage,
            direction = initialDirection,
            fitMode = initialFitMode,
            preloadWindow = preloadWindowFor(initialPage),
        )
    )
    val state: StateFlow<ReaderState> = _state.asStateFlow()

    /** 현재 페이지에 대한 영속 식별자. ViewModel 외부에서 Resume 저장 시 사용. */
    val positionKey: PositionKey
        get() = PositionKey(bookId, state.value.currentPage)

    private var preloadJob: Job? = null

    /** 다음 페이지 (방향 무관 — UI 레이어가 RTL 반전을 처리해서 호출). */
    fun goNext() = goTo(state.value.currentPage + 1)

    fun goPrevious() = goTo(state.value.currentPage - 1)

    fun goTo(pageIndex: Int) {
        val clamped = pageIndex.coerceIn(0, pageCount - 1)
        if (clamped == state.value.currentPage) return
        _state.value = _state.value.copy(
            currentPage = clamped,
            preloadWindow = preloadWindowFor(clamped),
        )
        triggerPreload()
    }

    fun setDirection(direction: ReadingDirection) {
        if (direction == state.value.direction) return
        _state.value = _state.value.copy(direction = direction)
    }

    fun setFitMode(fitMode: FitMode) {
        if (fitMode == state.value.fitMode) return
        _state.value = _state.value.copy(fitMode = fitMode)
        triggerPreload() // fit 변경 → 디코드 해상도 갱신 필요
    }

    /**
     * 현재 viewport 크기가 결정되면 호출 — 페이지 캐시 효율을 위해 디코드 해상도를
     * 본문 영역 크기와 묶는다. 본문 뷰가 onLayout 시점에 1회 부른다.
     */
    fun onViewportChanged(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        val current = _state.value
        if (current.viewportWidth == width && current.viewportHeight == height) return
        _state.value = current.copy(viewportWidth = width, viewportHeight = height)
        triggerPreload()
    }

    private fun triggerPreload() {
        val s = _state.value
        if (s.viewportWidth <= 0 || s.viewportHeight <= 0) return
        preloadJob?.cancel()
        preloadJob = viewModelScope.launch {
            // 현재 → 인접한 순서로 디코드. UI에는 도착하는 대로 캐시에 들어간다고 가정.
            for (idx in s.preloadWindow.byProximityTo(s.currentPage)) {
                decoder.decode(idx, s.viewportWidth, s.viewportHeight)
            }
        }
    }

    private fun preloadWindowFor(currentPage: Int): IntRange {
        val from = (currentPage - PRELOAD_RADIUS).coerceAtLeast(0)
        val to = (currentPage + PRELOAD_RADIUS).coerceAtMost(pageCount - 1)
        return from..to
    }

    companion object {
        /** PRD §6.1: ±3 프리로드. 3GB RAM·RK3566 기준 ARGB8888 8MB×7장 ≈ 56MB. */
        const val PRELOAD_RADIUS = 3
    }
}

/** ReaderViewModel이 외부로 노출하는 불변 상태 스냅샷. */
data class ReaderState(
    val currentPage: Int,
    val direction: ReadingDirection,
    val fitMode: FitMode,
    val preloadWindow: IntRange,
    val viewportWidth: Int = 0,
    val viewportHeight: Int = 0,
)

private fun IntRange.byProximityTo(center: Int): List<Int> {
    val list = toList()
    return list.sortedBy { kotlin.math.abs(it - center) }
}
