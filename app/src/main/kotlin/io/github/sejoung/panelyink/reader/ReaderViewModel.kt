package io.github.sejoung.panelyink.reader

import io.github.sejoung.panelyink.core.fit.FitMode
import io.github.sejoung.panelyink.core.position.PositionKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 본문 리더 상태 머신. PRD §6.1 읽기 코어와 §6.1 입력 정책의 공통 진입점.
 *
 * androidx.lifecycle.ViewModel 을 상속하지 **않는다** — Compose 라이프사이클
 * (`remember(session)`)에 1:1로 묶이기 때문. ViewModelStore에 캐시되면 책을
 * 닫고 다시 열었을 때 stale decoder(닫힌 ZipFile)를 잡고 있어 다음 디코드 시
 * `IllegalStateException: zip file closed`가 발생한다.
 *
 * 자체 [scope]를 가지며 [close] 시 cancel. ReaderScreen의 DisposableEffect에서
 * 호출.
 *
 * v1.0 책임:
 * - 현재 페이지 / 방향 / fit 모드 보유
 * - 프리로드 윈도(±3) 계산 및 디코드 트리거
 * - PositionKey 노출 (Resume의 1차 키)
 *
 * v1.0 비책임:
 * - 페이지 캐시 정책 (CbzBookSession 안 LruCache)
 * - 풀리프레시 주기 (View 레이어 결정)
 */
class ReaderViewModel(
    val bookId: String,
    val pageCount: Int,
    private val decoder: PageDecoder,
    initialPage: Int = 0,
    initialDirection: ReadingDirection = ReadingDirection.Ltr,
    initialFitMode: FitMode = FitMode.FitScreen,
) {

    init {
        require(pageCount > 0) { "pageCount must be > 0" }
        require(initialPage in 0 until pageCount) { "initialPage out of range" }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _state = MutableStateFlow(
        ReaderState(
            currentPage = initialPage,
            direction = initialDirection,
            fitMode = initialFitMode,
            preloadWindow = preloadWindowFor(initialPage),
        )
    )
    val state: StateFlow<ReaderState> = _state.asStateFlow()

    /** 디코드 1장이 캐시에 들어왔다는 신호. 본문 View가 invalidate 트리거로 사용. */
    private val _decoded = MutableSharedFlow<Int>(extraBufferCapacity = 16)
    val decoded: SharedFlow<Int> = _decoded.asSharedFlow()

    val positionKey: PositionKey
        get() = PositionKey(bookId, state.value.currentPage)

    private var preloadJob: Job? = null

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
        triggerPreload()
    }

    fun onViewportChanged(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        val current = _state.value
        if (current.viewportWidth == width && current.viewportHeight == height) return
        _state.value = current.copy(viewportWidth = width, viewportHeight = height)
        triggerPreload()
    }

    /** Compose 라이프사이클 종료 시 호출. 진행 중인 디코드를 cancel하고 scope 해제. */
    fun close() {
        scope.cancel()
    }

    private fun triggerPreload() {
        val s = _state.value
        if (s.viewportWidth <= 0 || s.viewportHeight <= 0) return
        preloadJob?.cancel()
        preloadJob = scope.launch {
            for (idx in s.preloadWindow.byProximityTo(s.currentPage)) {
                decoder.decode(idx, s.viewportWidth, s.viewportHeight)
                _decoded.emit(idx)
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
