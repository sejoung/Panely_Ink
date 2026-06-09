package io.github.sejoung.panelyink.reader

import io.github.sejoung.panelyink.core.fit.FitMode
import io.github.sejoung.panelyink.core.position.PositionKey
import io.github.sejoung.panelyink.core.preferences.AppPreferences
import io.github.sejoung.panelyink.core.preferences.DEFAULT_FULL_REFRESH_INTERVAL
import io.github.sejoung.panelyink.core.preferences.ReaderOrientation
import io.github.sejoung.panelyink.core.preferences.ReadingDirection
import io.github.sejoung.panelyink.reader.model.BookSettings
import io.github.sejoung.panelyink.reader.model.BookSettingsOverrides
import io.github.sejoung.panelyink.reader.session.DecodedPage
import io.github.sejoung.panelyink.reader.session.PageDecoder
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
    fun initialAppPreferencesAreApplied() {
        val vm = ReaderViewModel(
            bookId = "b",
            pageCount = 100,
            decoder = FakePageDecoder(),
            initialAppPreferences = AppPreferences(
                defaultReadingDirection = ReadingDirection.Rtl,
                fullRefreshInterval = 10,
                invertEnabled = true,
                languageTag = "ko",
                defaultSpreadMode = false,
                defaultOrientation = ReaderOrientation.Portrait,
                defaultTrimEnabled = false,
                defaultFitMode = FitMode.FitScreen,
                defaultContrast = 1.0f,
            ),
        )

        assertEquals(10, vm.state.value.fullRefreshInterval)
        assertEquals(true, vm.state.value.invertEnabled)
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
    fun trimEnabledIsPassedToDecoderAndEnablingRetriggersPreload() = runTest {
        val decoder = FakePageDecoder()
        val vm = ReaderViewModel("b", 10, decoder)
        vm.setTrimEnabled(false)
        vm.onViewportChanged(800, 1200)
        runCurrent()
        assertTrue(decoder.trimFlags.isNotEmpty())
        assertTrue(decoder.trimFlags.all { !it })

        decoder.trimFlags.clear()
        vm.setTrimEnabled(true)
        runCurrent()
        assertTrue(decoder.trimFlags.isNotEmpty())
        assertTrue(decoder.trimFlags.all { it })
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

    @Test(expected = IllegalArgumentException::class)
    fun pageCountMustBePositive() {
        ReaderViewModel("b", 0, FakePageDecoder())
    }

    @Test(expected = IllegalArgumentException::class)
    fun initialPageMustBeInRange() {
        ReaderViewModel("b", 5, FakePageDecoder(), initialPage = 5)
    }

    @Test
    fun fullRefreshTriggersEveryNthPageWhenEnabled() {
        val vm = ReaderViewModel("b", 100, FakePageDecoder())
        vm.setFullRefreshInterval(5)
        // N=5. 4번까진 generation 그대로, 5번째에 1로 증가.
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
        vm.setFullRefreshInterval(5)
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
            DEFAULT_FULL_REFRESH_INTERVAL,
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

    @Test
    fun setInvertEnabledUpdatesState() {
        val vm = ReaderViewModel("b", 10, FakePageDecoder())
        assertEquals(false, vm.state.value.invertEnabled)
        vm.setInvertEnabled(true)
        assertEquals(true, vm.state.value.invertEnabled)
        vm.close()
    }

    @Test
    fun spreadModeDefaultsOff() {
        val vm = ReaderViewModel("b", 10, FakePageDecoder())
        assertEquals(false, vm.state.value.spreadMode)
        vm.close()
    }

    @Test
    fun setSpreadModeOnAlignsCurrentPageToEven() {
        val vm = ReaderViewModel("b", 100, FakePageDecoder(), initialPage = 7)
        vm.setSpreadMode(true)
        assertEquals(true, vm.state.value.spreadMode)
        assertEquals(6, vm.state.value.currentPage)
        vm.close()
    }

    @Test
    fun setSpreadModeOffLeavesPageUntouched() {
        val vm = ReaderViewModel(
            "b", 100, FakePageDecoder(),
            initialPage = 6,
            initialBookSettings = BookSettings.DEFAULTS.copy(spreadMode = true),
        )
        assertEquals(6, vm.state.value.currentPage)
        vm.setSpreadMode(false)
        assertEquals(6, vm.state.value.currentPage)
        assertEquals(false, vm.state.value.spreadMode)
        vm.close()
    }

    @Test
    fun initialSpreadModeAlignsInitialPage() {
        val vm = ReaderViewModel(
            "b", 100, FakePageDecoder(),
            initialPage = 11,
            initialBookSettings = BookSettings.DEFAULTS.copy(spreadMode = true),
        )
        assertEquals(10, vm.state.value.currentPage)
        assertEquals(true, vm.state.value.spreadMode)
        vm.close()
    }

    @Test
    fun goNextStepsTwoInSpreadMode() {
        val vm = ReaderViewModel(
            "b", 100, FakePageDecoder(),
            initialBookSettings = BookSettings.DEFAULTS.copy(spreadMode = true),
        )
        vm.goNext()
        assertEquals(2, vm.state.value.currentPage)
        vm.goNext()
        assertEquals(4, vm.state.value.currentPage)
        vm.close()
    }

    @Test
    fun goPreviousStepsTwoInSpreadMode() {
        val vm = ReaderViewModel(
            "b", 100, FakePageDecoder(),
            initialPage = 10,
            initialBookSettings = BookSettings.DEFAULTS.copy(spreadMode = true),
        )
        vm.goPrevious()
        assertEquals(8, vm.state.value.currentPage)
        vm.goPrevious()
        assertEquals(6, vm.state.value.currentPage)
        vm.close()
    }

    @Test
    fun goToSnapsToEvenInSpreadMode() {
        val vm = ReaderViewModel(
            "b", 100, FakePageDecoder(),
            initialBookSettings = BookSettings.DEFAULTS.copy(spreadMode = true),
        )
        vm.goTo(15)
        assertEquals(14, vm.state.value.currentPage)
        vm.goTo(13)
        assertEquals(12, vm.state.value.currentPage)
        vm.close()
    }

    @Test
    fun goNextClampsAtLastPageInSpreadMode() {
        // 홀수 페이지 수 — 마지막 spread는 leading만 있고 secondary는 비어있음
        val vm = ReaderViewModel(
            "b", 7, FakePageDecoder(),
            initialPage = 4,
            initialBookSettings = BookSettings.DEFAULTS.copy(spreadMode = true),
        )
        vm.goNext()
        assertEquals(6, vm.state.value.currentPage)
        vm.goNext()
        // 이미 마지막. 6이 leading이고 secondary는 없으니 더 진행 불가.
        assertEquals(6, vm.state.value.currentPage)
        vm.close()
    }

    @Test
    fun goNextClampsAtLastSpreadWithEvenPageCount() {
        // 짝수 페이지 수 — 마지막 전체 spread는 (6,7). 거기서 한 번 더 넘겨도 leading은
        // 짝수(6)에 머물러야 한다. 정렬→클램프만 하면 align(8)=8→clamp(7)로 홀수에 떨어져
        // 페이지 6이 사라지고 7만 단독 노출되는 회귀가 발생한다.
        val vm = ReaderViewModel(
            "b", 8, FakePageDecoder(),
            initialPage = 6,
            initialBookSettings = BookSettings.DEFAULTS.copy(spreadMode = true),
        )
        assertEquals(6, vm.state.value.currentPage)
        vm.goNext()
        assertEquals(6, vm.state.value.currentPage)
        vm.close()
    }

    @Test
    fun goToBeyondEndSnapsToLastLeadingWithEvenPageCount() {
        val vm = ReaderViewModel(
            "b", 8, FakePageDecoder(),
            initialBookSettings = BookSettings.DEFAULTS.copy(spreadMode = true),
        )
        vm.goTo(7) // 마지막 페이지(홀수)로 점프 → leading 6에 안착
        assertEquals(6, vm.state.value.currentPage)
        vm.goTo(99) // 범위 초과 → 마지막 leading(6)
        assertEquals(6, vm.state.value.currentPage)
        vm.close()
    }

    @Test
    fun coverAloneShowsCoverThenPairsForward() {
        // coverAlone: 0 단독 → (1,2) → (3,4) → (5,6) …
        val vm = ReaderViewModel(
            "b", 10, FakePageDecoder(),
            initialBookSettings = BookSettings.DEFAULTS.copy(spreadMode = true, coverAlone = true),
        )
        assertEquals(0, vm.state.value.currentPage) // 표지 단독
        vm.goNext()
        assertEquals(1, vm.state.value.currentPage) // (1,2)
        vm.goNext()
        assertEquals(3, vm.state.value.currentPage) // (3,4)
        vm.goNext()
        assertEquals(5, vm.state.value.currentPage) // (5,6)
        vm.close()
    }

    @Test
    fun coverAloneGoPreviousReturnsToCover() {
        val vm = ReaderViewModel(
            "b", 10, FakePageDecoder(),
            initialPage = 3,
            initialBookSettings = BookSettings.DEFAULTS.copy(spreadMode = true, coverAlone = true),
        )
        assertEquals(3, vm.state.value.currentPage) // (3,4)
        vm.goPrevious()
        assertEquals(1, vm.state.value.currentPage) // (1,2)
        vm.goPrevious()
        assertEquals(0, vm.state.value.currentPage) // 표지 단독
        vm.goPrevious()
        assertEquals(0, vm.state.value.currentPage) // no-op
        vm.close()
    }

    @Test
    fun coverAloneInitialPageAlignsToOddLeading() {
        // coverAlone에서 4쪽은 (3,4) 펼침면의 trailing → leading 3으로 정렬.
        val vm = ReaderViewModel(
            "b", 10, FakePageDecoder(),
            initialPage = 4,
            initialBookSettings = BookSettings.DEFAULTS.copy(spreadMode = true, coverAlone = true),
        )
        assertEquals(3, vm.state.value.currentPage)
        vm.close()
    }

    @Test
    fun setCoverAloneRealignsCurrentPageAndRecordsOverride() {
        // 두쪽 보기 중 (2,3) 펼침면에서 표지 단독을 켜면 페어링 parity가 바뀌어 2쪽은 (1,2)의 trailing →
        // leading 1로 재정렬되어야 한다.
        val vm = ReaderViewModel(
            "b", 10, FakePageDecoder(),
            initialPage = 2,
            initialBookSettings = BookSettings.DEFAULTS.copy(spreadMode = true),
        )
        assertEquals(2, vm.state.value.currentPage)
        vm.setCoverAlone(true)
        assertEquals(true, vm.state.value.coverAlone)
        assertEquals(1, vm.state.value.currentPage)
        assertEquals(true, vm.overrides.value.coverAlone)
        vm.close()
    }

    @Test
    fun setCoverAloneWhileSinglePageKeepsPageButRecordsOverride() {
        // 단쪽 보기에서 토글하면 페이지는 그대로(정렬은 두쪽 켤 때 적용)지만 override는 기록.
        val vm = ReaderViewModel(
            "b", 10, FakePageDecoder(),
            initialPage = 5,
        )
        vm.setCoverAlone(true)
        assertEquals(5, vm.state.value.currentPage)
        assertEquals(true, vm.state.value.coverAlone)
        assertEquals(true, vm.overrides.value.coverAlone)
        vm.close()
    }

    // ── 디코드 해상도: 두쪽 페어는 절반 폭, 단독 슬롯(표지 단독·마지막 한 장)·단쪽은 전체 폭 ──

    @Test
    fun spreadPairDecodesAtHalfViewportWidth() = runTest {
        val decoder = FakePageDecoder()
        // 단쪽 page 0 → 페어 (0,1).
        val vm = ReaderViewModel(
            "b", 100, decoder,
            initialBookSettings = BookSettings.DEFAULTS.copy(spreadMode = true),
        )
        vm.onViewportChanged(800, 1200)
        runCurrent()
        assertTrue(decoder.viewports.isNotEmpty())
        // trigger 한 번은 단일 decodeViewport를 쓰므로 모든 디코드가 절반 폭(400).
        assertTrue(
            "페어는 절반 폭 디코드여야 함, 실제: ${decoder.viewports}",
            decoder.viewports.all { it == 400 to 1200 },
        )
        vm.close()
    }

    @Test
    fun coverAloneCoverDecodesAtFullViewportWidth() = runTest {
        val decoder = FakePageDecoder()
        val vm = ReaderViewModel(
            "b", 100, decoder,
            initialBookSettings = BookSettings.DEFAULTS.copy(spreadMode = true, coverAlone = true),
        )
        vm.onViewportChanged(800, 1200)
        runCurrent()
        // 표지 단독은 전체 폭 중앙으로 그려지므로 전체 해상도(800)로 디코드.
        assertTrue(
            "표지 단독은 전체 폭 디코드여야 함, 실제: ${decoder.viewports}",
            decoder.viewports.isNotEmpty() && decoder.viewports.all { it == 800 to 1200 },
        )
        vm.close()
    }

    @Test
    fun lastLonePageDecodesAtFullViewportWidth() = runTest {
        val decoder = FakePageDecoder()
        // 홀수 7쪽, 마지막 leading 6 → secondary 없음(단독 슬롯).
        val vm = ReaderViewModel(
            "b", 7, decoder,
            initialPage = 6,
            initialBookSettings = BookSettings.DEFAULTS.copy(spreadMode = true),
        )
        vm.onViewportChanged(800, 1200)
        runCurrent()
        assertTrue(
            "마지막 단독 한 장은 전체 폭 디코드여야 함, 실제: ${decoder.viewports}",
            decoder.viewports.isNotEmpty() && decoder.viewports.all { it == 800 to 1200 },
        )
        vm.close()
    }

    @Test
    fun decodeResolutionSwitchesWhenLeavingCoverAloneCover() = runTest {
        val decoder = FakePageDecoder()
        val vm = ReaderViewModel(
            "b", 100, decoder,
            initialBookSettings = BookSettings.DEFAULTS.copy(spreadMode = true, coverAlone = true),
        )
        vm.onViewportChanged(800, 1200)
        runCurrent()
        assertTrue(decoder.viewports.all { it == 800 to 1200 }) // 표지: 전체 폭

        decoder.viewports.clear()
        vm.goNext() // 표지 → 페어 (1,2)
        runCurrent()
        assertTrue(
            "페어로 넘어가면 절반 폭으로 전환되어야 함, 실제: ${decoder.viewports}",
            decoder.viewports.isNotEmpty() && decoder.viewports.all { it == 400 to 1200 },
        )
        vm.close()
    }

    @Test
    fun singlePageModeDecodesAtFullViewportWidth() = runTest {
        val decoder = FakePageDecoder()
        val vm = ReaderViewModel("b", 100, decoder, initialPage = 5)
        vm.onViewportChanged(800, 1200)
        runCurrent()
        assertTrue(
            "단쪽 모드는 전체 폭 디코드여야 함, 실제: ${decoder.viewports}",
            decoder.viewports.isNotEmpty() && decoder.viewports.all { it == 800 to 1200 },
        )
        vm.close()
    }

    @Test
    fun orientationDefaultsPortrait() {
        val vm = ReaderViewModel("b", 10, FakePageDecoder())
        assertEquals(ReaderOrientation.Portrait, vm.state.value.orientation)
        vm.close()
    }

    @Test
    fun setOrientationUpdatesState() {
        val vm = ReaderViewModel("b", 10, FakePageDecoder())
        vm.setOrientation(ReaderOrientation.Landscape)
        assertEquals(ReaderOrientation.Landscape, vm.state.value.orientation)
        vm.setOrientation(ReaderOrientation.Portrait)
        assertEquals(ReaderOrientation.Portrait, vm.state.value.orientation)
        vm.close()
    }

    @Test
    fun initialOrientationFromBookSettings() {
        val vm = ReaderViewModel(
            "b", 10, FakePageDecoder(),
            initialBookSettings = BookSettings.DEFAULTS.copy(orientation = ReaderOrientation.Landscape),
        )
        assertEquals(ReaderOrientation.Landscape, vm.state.value.orientation)
        vm.close()
    }

    @Test
    fun overridesEmptyInitially() {
        val vm = ReaderViewModel("b", 10, FakePageDecoder())
        assertEquals(BookSettingsOverrides.NONE, vm.overrides.value)
        vm.close()
    }

    @Test
    fun settersRecordOverridesSparsely() {
        val vm = ReaderViewModel("b", 10, FakePageDecoder())
        // 사용자가 spread만 토글 — 다른 필드는 null 유지
        vm.setSpreadMode(true)
        val ov = vm.overrides.value
        assertEquals(true, ov.spreadMode)
        assertEquals(null, ov.orientation)
        assertEquals(null, ov.direction)
        assertEquals(null, ov.fitMode)
        assertEquals(null, ov.trimEnabled)
        assertEquals(null, ov.contrast)
        vm.close()
    }

    @Test
    fun initialOverridesAreUsedForOverridesState() {
        val initial = BookSettingsOverrides(orientation = ReaderOrientation.Landscape)
        val vm = ReaderViewModel(
            "b", 10, FakePageDecoder(),
            initialBookSettings = BookSettings.DEFAULTS.copy(orientation = ReaderOrientation.Landscape),
            initialOverrides = initial,
        )
        assertEquals(initial, vm.overrides.value)
        vm.close()
    }

    @Test
    fun spreadModePreloadsBothVisiblePagesFirst() = runTest {
        // Phase 1: leading(N)과 secondary(N+1) **병렬** 디코드 → 호출 순서는 비결정적이지만
        //         두 페이지가 모두 가장 먼저 디코드되어야 한다.
        // Phase 2: 나머지는 proximity 순서 sequential (9, 12, 8, 13, 7).
        val decoder = FakePageDecoder()
        val vm = ReaderViewModel(
            "b", 100, decoder,
            initialPage = 10,
            initialBookSettings = BookSettings.DEFAULTS.copy(spreadMode = true),
        )
        vm.onViewportChanged(1648, 1236)
        runCurrent()
        // 첫 2개 호출은 (10, 11) 또는 (11, 10) — 병렬 launch라 순서 불확정.
        assertEquals(setOf(10, 11), decoder.calls.take(2).toSet())
        // 나머지 5개는 sequential proximity 순서.
        assertEquals(listOf(9, 12, 8, 13, 7), decoder.calls.drop(2))
        vm.close()
    }

    @Test
    fun spreadModeDecodesAtHalfWidthViewport() = runTest {
        // spread 모드는 각 페이지가 화면 절반에 그려지므로 디코더에 절반 viewport hint를 줘야
        // inSampleSize가 적절히 계산되어 메모리/디코드 시간이 줄어든다.
        val decoder = FakePageDecoder()
        val vm = ReaderViewModel(
            "b", 100, decoder,
            initialBookSettings = BookSettings.DEFAULTS.copy(spreadMode = true),
        )
        vm.onViewportChanged(1648, 1236)
        runCurrent()
        // 1648 → 절반 824. 모든 디코드 호출이 (824, 1236)을 받아야 한다.
        assertEquals(824 to 1236, decoder.viewports.first())
        vm.close()
    }

    @Test
    fun spreadModeAtLastPageOnlyHasLeadingCenter() = runTest {
        // 홀수 페이지 책의 마지막 spread는 leading만 있고 secondary는 범위 밖.
        // proximity center가 leading 1개로 폴백되어야 한다.
        val decoder = FakePageDecoder()
        val vm = ReaderViewModel(
            "b", 7, decoder,
            initialPage = 6, // 마지막 페이지 (페이지 수 7, 인덱스 6)
            initialBookSettings = BookSettings.DEFAULTS.copy(spreadMode = true),
        )
        vm.onViewportChanged(1648, 1236)
        runCurrent()
        // currentPage=6 (이미 짝수 정렬 후), pageCount-1=6 → secondary=7은 범위 밖.
        // 단일 중심 proximity로 6 → 5 → 4 → 3 → ... 순.
        assertEquals(listOf(6, 5, 4, 3), decoder.calls)
        vm.close()
    }

    @Test
    fun spreadModeKeepsVisiblePagesWarmBeforeEachDecode() = runTest {
        // 캐시 LRU eviction에서 visible 페이지 보호 — 모든 디코드 직전에 (N, N+1)을 keepWarm으로 touch.
        // Phase 1(병렬) 2회 + Phase 2(sequential) 5회 = 7회.
        val decoder = FakePageDecoder()
        val vm = ReaderViewModel(
            "b", 100, decoder,
            initialPage = 10,
            initialBookSettings = BookSettings.DEFAULTS.copy(spreadMode = true),
        )
        vm.onViewportChanged(1648, 1236)
        runCurrent()
        assertEquals(7, decoder.keepWarmCalls.size)
        assertTrue(decoder.keepWarmCalls.all { it == listOf(10, 11) })
        vm.close()
    }

    @Test
    fun singleModeKeepsCurrentPageWarm() = runTest {
        // 단쪽 모드에서도 currentPage를 keepWarm — visible 1장만.
        val decoder = FakePageDecoder()
        val vm = ReaderViewModel("b", 100, decoder, initialPage = 5)
        vm.onViewportChanged(800, 1200)
        runCurrent()
        assertTrue(decoder.keepWarmCalls.isNotEmpty())
        assertTrue(decoder.keepWarmCalls.all { it == listOf(5) })
        vm.close()
    }

    @Test
    fun fullRefreshCounterDoublesInSpreadMode() {
        val vm = ReaderViewModel(
            "b", 100, FakePageDecoder(),
            initialBookSettings = BookSettings.DEFAULTS.copy(spreadMode = true),
        )
        vm.setFullRefreshInterval(4)
        // 한 번 이동 = 2쪽 변화. interval=4면 두 번째 spread 전환에서 트리거.
        vm.goNext()
        assertEquals(0, vm.state.value.fullRefreshGeneration)
        vm.goNext()
        assertEquals(1, vm.state.value.fullRefreshGeneration)
        vm.close()
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
    val trimFlags = mutableListOf<Boolean>()
    val keepWarmCalls = mutableListOf<List<Int>>()

    override suspend fun decode(
        pageIndex: Int,
        viewportWidth: Int,
        viewportHeight: Int,
        trimEnabled: Boolean,
    ): DecodedPage {
        calls += pageIndex
        viewports += viewportWidth to viewportHeight
        trimFlags += trimEnabled
        if (pageIndex in suspendOn && gate != null) gate.await()
        return DecodedPage(pageIndex, viewportWidth, viewportHeight, Any())
    }

    override fun keepWarm(visibleIndices: IntArray) {
        keepWarmCalls += visibleIndices.toList()
    }
}
