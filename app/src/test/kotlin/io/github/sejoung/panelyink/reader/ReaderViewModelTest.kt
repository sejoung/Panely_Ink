package io.github.sejoung.panelyink.reader

import io.github.sejoung.panelyink.core.fit.FitMode
import io.github.sejoung.panelyink.core.position.PositionKey
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [ReaderViewModel] JVM 단위 테스트.
 *
 * - 안드로이드 의존성 없음 (PageDecoder 추상화를 fake로 바꿔서 검증)
 * - UnconfinedTestDispatcher로 `Dispatchers.Main.immediate` 매핑 → launch 본문이
 *   호출자 컨텍스트에서 즉시 실행되어 preload 효과를 동기적으로 검증
 *
 * 핵심 검증 영역:
 * - 페이지 이동/clamp/no-op
 * - preload window 양 끝 잘림
 * - 가까운 페이지부터 디코드 (byProximityTo)
 * - viewport / fit 변경 시 preload 재트리거
 * - close 후 추가 preload 안 됨
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReaderViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun initialStateMatchesArguments() {
        val vm = ReaderViewModel(
            bookId = "b",
            pageCount = 100,
            decoder = FakePageDecoder(),
        )
        val s = vm.state.value
        assertEquals(0, s.currentPage)
        assertEquals(FitMode.FitScreen, s.fitMode)
        assertEquals(ReadingDirection.Ltr, s.direction)
        assertEquals(0..3, s.preloadWindow)
        assertEquals(0, s.viewportWidth)
        assertEquals(0, s.viewportHeight)
        assertEquals(PositionKey("b", 0), vm.positionKey)
        vm.close()
    }

    @Test
    fun goNextIncrementsPage() {
        val vm = ReaderViewModel("b", 10, FakePageDecoder())
        vm.goNext()
        assertEquals(1, vm.state.value.currentPage)
        vm.close()
    }

    @Test
    fun goPreviousDecrementsPage() {
        val vm = ReaderViewModel("b", 10, FakePageDecoder(), initialPage = 5)
        vm.goPrevious()
        assertEquals(4, vm.state.value.currentPage)
        vm.close()
    }

    @Test
    fun goToClampsBelowZero() {
        val vm = ReaderViewModel("b", 10, FakePageDecoder(), initialPage = 5)
        vm.goTo(-3)
        assertEquals(0, vm.state.value.currentPage)
        vm.close()
    }

    @Test
    fun goToClampsAtPageCount() {
        val vm = ReaderViewModel("b", 10, FakePageDecoder())
        vm.goTo(99)
        assertEquals(9, vm.state.value.currentPage)
        vm.close()
    }

    @Test
    fun goToSamePageIsNoOp() {
        val vm = ReaderViewModel("b", 10, FakePageDecoder(), initialPage = 5)
        val before = vm.state.value
        vm.goTo(5)
        assertSame("state 인스턴스가 그대로여야 함", before, vm.state.value)
        vm.close()
    }

    @Test
    fun preloadWindowClampsAtEnd() {
        val vm = ReaderViewModel("b", 5, FakePageDecoder(), initialPage = 4)
        assertEquals(1..4, vm.state.value.preloadWindow)
        vm.close()
    }

    @Test
    fun preloadWindowClampsAtStart() {
        val vm = ReaderViewModel("b", 100, FakePageDecoder(), initialPage = 1)
        assertEquals(0..4, vm.state.value.preloadWindow)
        vm.close()
    }

    @Test
    fun setFitModeUpdatesState() {
        val vm = ReaderViewModel("b", 10, FakePageDecoder())
        vm.setFitMode(FitMode.FitWidth)
        assertEquals(FitMode.FitWidth, vm.state.value.fitMode)
        vm.close()
    }

    @Test
    fun setFitModeSameValueIsNoOp() {
        val vm = ReaderViewModel("b", 10, FakePageDecoder())
        val before = vm.state.value
        vm.setFitMode(FitMode.FitScreen)
        assertSame(before, vm.state.value)
        vm.close()
    }

    @Test
    fun setDirectionUpdatesState() {
        val vm = ReaderViewModel("b", 10, FakePageDecoder())
        vm.setDirection(ReadingDirection.Rtl)
        assertEquals(ReadingDirection.Rtl, vm.state.value.direction)
        vm.close()
    }

    @Test
    fun viewportZeroDoesNotTriggerPreload() = runTest {
        val decoder = FakePageDecoder()
        val vm = ReaderViewModel("b", 10, decoder)
        vm.onViewportChanged(0, 1200)
        vm.onViewportChanged(800, -1)
        runCurrent()
        assertTrue(
            "viewport <= 0이면 디코드 호출 없어야 함, 실제: ${decoder.calls}",
            decoder.calls.isEmpty(),
        )
        vm.close()
    }

    @Test
    fun viewportChangePreloadsByProximity() = runTest {
        val decoder = FakePageDecoder()
        val vm = ReaderViewModel("b", 100, decoder, initialPage = 5)
        vm.onViewportChanged(800, 1200)
        runCurrent()
        // window 2..8, currentPage 5 기준 거리 순:
        //   |i-5|=0 → 5, |i-5|=1 → 4·6, =2 → 3·7, =3 → 2·8
        // sortedBy는 stable이므로 같은 거리에선 원본 순서(2,3,4,…) 유지 → 4 우선
        assertEquals(listOf(5, 4, 6, 3, 7, 2, 8), decoder.calls)
        assertEquals(800 to 1200, decoder.viewports.last())
        vm.close()
    }

    @Test
    fun fitModeChangeRetriggersPreload() = runTest {
        val decoder = FakePageDecoder()
        val vm = ReaderViewModel("b", 10, decoder)
        vm.onViewportChanged(800, 1200)
        runCurrent()
        val countAfterFirst = decoder.calls.size
        vm.setFitMode(FitMode.FitWidth)
        runCurrent()
        assertTrue(
            "fit 변경 시 preload가 재실행되어야 함",
            decoder.calls.size > countAfterFirst,
        )
        vm.close()
    }

    @Test
    fun goToCancelsInflightPreload() = runTest {
        val gate = CompletableDeferred<Unit>()
        // 첫 호출만 막아서 cancel 검증
        val decoder = FakePageDecoder(suspendOn = setOf(0), gate = gate)
        val vm = ReaderViewModel("b", 100, decoder)
        vm.onViewportChanged(800, 1200)
        runCurrent()
        // 페이지 0 디코드 진행 중(gate 대기). 다른 페이지는 아직 호출 안 됨.
        assertEquals(listOf(0), decoder.calls)

        vm.goTo(50)
        runCurrent()
        // 새 윈도우 디코드 시작. 멈춰있던 0번은 cancel되어 gate를 풀어도 진행 X
        gate.complete(Unit)
        runCurrent()
        assertEquals(50, vm.state.value.currentPage)
        assertTrue(
            "새 currentPage(50) 디코드가 호출되어야 함",
            decoder.calls.contains(50),
        )
        vm.close()
    }

    @Test
    fun closeStopsFurtherPreload() = runTest {
        val decoder = FakePageDecoder()
        val vm = ReaderViewModel("b", 10, decoder)
        vm.onViewportChanged(800, 1200)
        runCurrent()
        val countBeforeClose = decoder.calls.size
        vm.close()

        vm.setFitMode(FitMode.FitWidth)
        vm.goTo(5)
        runCurrent()
        assertEquals(
            "close 후엔 디코드 추가 호출 없어야 함",
            countBeforeClose,
            decoder.calls.size,
        )
    }

    @Test
    fun positionKeyTracksCurrentPage() {
        val vm = ReaderViewModel("book42", 100, FakePageDecoder(), initialPage = 7)
        assertEquals(PositionKey("book42", 7), vm.positionKey)
        vm.goTo(15)
        assertEquals(PositionKey("book42", 15), vm.positionKey)
        vm.close()
    }

    @Test
    fun stateInstanceChangesOnPageMove() {
        val vm = ReaderViewModel("b", 10, FakePageDecoder())
        val before = vm.state.value
        vm.goNext()
        assertNotEquals(before, vm.state.value)
        vm.close()
    }

    @Test(expected = IllegalArgumentException::class)
    fun pageCountMustBePositive() {
        ReaderViewModel("b", 0, FakePageDecoder())
    }

    @Test(expected = IllegalArgumentException::class)
    fun initialPageMustBeInRange() {
        ReaderViewModel("b", 5, FakePageDecoder(), initialPage = 5)
    }

    @Test
    fun fullRefreshTriggersEveryNthPage() {
        val vm = ReaderViewModel("b", 100, FakePageDecoder())
        // 기본 N=5. 4번까진 generation 그대로, 5번째에 1로 증가.
        repeat(4) { vm.goNext() }
        assertEquals(0, vm.state.value.fullRefreshGeneration)
        vm.goNext()
        assertEquals(1, vm.state.value.fullRefreshGeneration)
        // 다음 5페이지에 다시 1 증가.
        repeat(4) { vm.goNext() }
        assertEquals(1, vm.state.value.fullRefreshGeneration)
        vm.goNext()
        assertEquals(2, vm.state.value.fullRefreshGeneration)
        vm.close()
    }

    @Test
    fun samePageGoToDoesNotIncrementFullRefresh() {
        val vm = ReaderViewModel("b", 10, FakePageDecoder(), initialPage = 5)
        repeat(10) { vm.goTo(5) } // 항상 같은 페이지 → no-op
        assertEquals(0, vm.state.value.fullRefreshGeneration)
        vm.close()
    }

    @Test
    fun customFullRefreshInterval() {
        val vm = ReaderViewModel("b", 100, FakePageDecoder())
        vm.setFullRefreshInterval(2)
        vm.goNext()
        assertEquals(0, vm.state.value.fullRefreshGeneration)
        vm.goNext()
        assertEquals(1, vm.state.value.fullRefreshGeneration)
        vm.goNext()
        vm.goNext()
        assertEquals(2, vm.state.value.fullRefreshGeneration)
        vm.close()
    }

    @Test(expected = IllegalArgumentException::class)
    fun fullRefreshIntervalRejectsNegative() {
        val vm = ReaderViewModel("b", 10, FakePageDecoder())
        try {
            vm.setFullRefreshInterval(-1)
        } finally {
            vm.close()
        }
    }

    @Test
    fun fullRefreshIntervalZeroDisablesAutoTrigger() {
        val vm = ReaderViewModel("b", 100, FakePageDecoder())
        vm.setFullRefreshInterval(0)
        assertEquals(0, vm.state.value.fullRefreshInterval)
        // 끔 상태에서는 페이지 이동에 generation 증가 없음
        repeat(20) { vm.goNext() }
        assertEquals(0, vm.state.value.fullRefreshGeneration)
        vm.close()
    }

    @Test
    fun triggerFullRefreshIncrementsGenerationImmediately() {
        val vm = ReaderViewModel("b", 100, FakePageDecoder())
        assertEquals(0, vm.state.value.fullRefreshGeneration)
        vm.triggerFullRefresh()
        assertEquals(1, vm.state.value.fullRefreshGeneration)
        vm.triggerFullRefresh()
        assertEquals(2, vm.state.value.fullRefreshGeneration)
        vm.close()
    }

    @Test
    fun triggerFullRefreshResetsCounter() {
        val vm = ReaderViewModel("b", 100, FakePageDecoder())
        // 4페이지 진행 (자동 트리거 직전)
        repeat(4) { vm.goNext() }
        assertEquals(0, vm.state.value.fullRefreshGeneration)
        // 강제 트리거 — 1로 즉시 증가, 카운터 리셋
        vm.triggerFullRefresh()
        assertEquals(1, vm.state.value.fullRefreshGeneration)
        // 4페이지 더 — 자동 트리거 안 됨(카운터가 리셋됐으니)
        repeat(4) { vm.goNext() }
        assertEquals(1, vm.state.value.fullRefreshGeneration)
        // 5번째에 자동 트리거
        vm.goNext()
        assertEquals(2, vm.state.value.fullRefreshGeneration)
        vm.close()
    }

    @Test
    fun setFullRefreshIntervalUpdatesState() {
        val vm = ReaderViewModel("b", 10, FakePageDecoder())
        assertEquals(
            ReaderViewModel.FULL_REFRESH_INTERVAL_DEFAULT,
            vm.state.value.fullRefreshInterval,
        )
        vm.setFullRefreshInterval(3)
        assertEquals(3, vm.state.value.fullRefreshInterval)
        vm.close()
    }

    @Test
    fun setContrastUpdatesState() {
        val vm = ReaderViewModel("b", 10, FakePageDecoder())
        assertEquals(1.0f, vm.state.value.contrast, 0.0001f)
        vm.setContrast(1.5f)
        assertEquals(1.5f, vm.state.value.contrast, 0.0001f)
        vm.close()
    }

    @Test
    fun setContrastSameValueIsNoOp() {
        val vm = ReaderViewModel("b", 10, FakePageDecoder())
        val before = vm.state.value
        vm.setContrast(1.0f)
        assertSame(before, vm.state.value)
        vm.close()
    }

    @Test(expected = IllegalArgumentException::class)
    fun setContrastBelowMinIsRejected() {
        val vm = ReaderViewModel("b", 10, FakePageDecoder())
        try {
            vm.setContrast(0.4f)
        } finally {
            vm.close()
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun setContrastAboveMaxIsRejected() {
        val vm = ReaderViewModel("b", 10, FakePageDecoder())
        try {
            vm.setContrast(2.1f)
        } finally {
            vm.close()
        }
    }
}

/**
 * 테스트용 fake 디코더. 호출 인덱스/뷰포트를 기록하고, 특정 인덱스에서만 [gate]를
 * 기다리도록 해서 cancel 동작을 관찰할 수 있다.
 */
private class FakePageDecoder(
    private val suspendOn: Set<Int> = emptySet(),
    private val gate: CompletableDeferred<Unit>? = null,
) : PageDecoder {
    val calls = mutableListOf<Int>()
    val viewports = mutableListOf<Pair<Int, Int>>()

    override suspend fun decode(
        pageIndex: Int,
        viewportWidth: Int,
        viewportHeight: Int,
    ): DecodedPage {
        calls += pageIndex
        viewports += viewportWidth to viewportHeight
        if (pageIndex in suspendOn && gate != null) gate.await()
        return DecodedPage(pageIndex, viewportWidth, viewportHeight, Any())
    }
}
