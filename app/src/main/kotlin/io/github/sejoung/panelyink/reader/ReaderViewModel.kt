package io.github.sejoung.panelyink.reader

import io.github.sejoung.panelyink.core.fit.FitMode
import io.github.sejoung.panelyink.core.position.PositionKey
import io.github.sejoung.panelyink.core.preferences.AppPreferences
import io.github.sejoung.panelyink.core.preferences.DEFAULT_FULL_REFRESH_INTERVAL
import io.github.sejoung.panelyink.core.preferences.ReaderOrientation
import io.github.sejoung.panelyink.core.preferences.ReadingDirection
import io.github.sejoung.panelyink.core.render.ContrastMatrix
import io.github.sejoung.panelyink.reader.model.BookSettings
import io.github.sejoung.panelyink.reader.model.BookSettingsOverrides
import io.github.sejoung.panelyink.reader.session.PageDecoder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
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
 * - 앱 전역 풀리프레시/흑백 반전 값을 상태에 반영
 *
 * v1.0 비책임:
 * - 페이지 캐시 정책 (CbzBookSession 안 LruCache)
 * - 풀리프레시 실제 렌더링 (ReaderView가 처리)
 */
class ReaderViewModel(
  val bookId: String,
  val pageCount: Int,
  private val decoder: PageDecoder,
  initialPage: Int = 0,
  initialBookSettings: BookSettings = BookSettings.DEFAULTS,
  initialOverrides: BookSettingsOverrides = BookSettingsOverrides.NONE,
  initialAppPreferences: AppPreferences = AppPreferences.DEFAULTS,
) {

  init {
    require(pageCount > 0) { "pageCount must be > 0" }
    require(initialPage in 0 until pageCount) { "initialPage out of range" }
  }

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

  private val _state = MutableStateFlow(
    run {
      // 두쪽 보기로 들어올 때 currentPage는 leading(짝수)으로 정렬 — 다시 책을 열어도
      // 동일한 spread가 보이도록.
      val alignedInitial = if (initialBookSettings.spreadMode) alignToSpreadLeading(initialPage)
      else initialPage
      ReaderState(
        currentPage = alignedInitial,
        direction = initialBookSettings.direction,
        fitMode = initialBookSettings.fitMode,
        preloadWindow = preloadWindowFor(alignedInitial),
        trimEnabled = initialBookSettings.trimEnabled,
        contrast = initialBookSettings.contrast,
        spreadMode = initialBookSettings.spreadMode,
        orientation = initialBookSettings.orientation,
        // 전역 prefs에서 받음 — 책별 저장은 안 함.
        invertEnabled = initialAppPreferences.invertEnabled,
        fullRefreshInterval = initialAppPreferences.fullRefreshInterval,
      )
    }
  )
  val state: StateFlow<ReaderState> = _state.asStateFlow()

  /**
   * 사용자가 명시적으로 변경한 필드들의 sparse 표현. 책별 영속 저장의 단위.
   * setter가 호출될 때마다 해당 필드가 non-null로 채워진다. 사용자가 손대지 않은 필드는 null로 남아
   * 다음 진입에 [AppPreferences] / [BookSettings.DEFAULTS]에서 합성된다.
   */
  private val _overrides = MutableStateFlow(initialOverrides)
  val overrides: StateFlow<BookSettingsOverrides> = _overrides.asStateFlow()

  /**
   * 마지막 풀리프레시 이후 페이지 변경 횟수. [ReaderState.fullRefreshInterval]에
   * 도달하면 [ReaderState.fullRefreshGeneration]을 1 증가시키고 reset.
   * interval=0이면 자동 트리거 비활성(사용자가 끔 선택), [triggerFullRefresh]만 동작.
   */
  private var pagesSinceFullRefresh = 0

  /** 디코드 1장이 캐시에 들어왔다는 신호. 본문 View가 invalidate 트리거로 사용. */
  private val _decoded = MutableSharedFlow<Int>(extraBufferCapacity = 16)
  val decoded: SharedFlow<Int> = _decoded.asSharedFlow()

  val positionKey: PositionKey
    get() = PositionKey(bookId, state.value.currentPage)

  private var preloadJob: Job? = null
  private var preloadGeneration = 0

  fun goNext() {
    val step = if (state.value.spreadMode) 2 else 1
    goTo(state.value.currentPage + step)
  }

  fun goPrevious() {
    val step = if (state.value.spreadMode) 2 else 1
    goTo(state.value.currentPage - step)
  }

  fun goTo(pageIndex: Int) {
    val current = _state.value
    // 두쪽 보기에서는 leading(짝수)으로 정렬 — 메뉴/북마크 점프도 spread 경계에 안착.
    val aligned = if (current.spreadMode) alignToSpreadLeading(pageIndex) else pageIndex
    val clamped = aligned.coerceIn(0, pageCount - 1)
    if (clamped == current.currentPage) return

    // 풀리프레시 정책 — N페이지마다 generation++ → ReaderView가 검정 한 프레임으로
    // e-ink 컨트롤러를 풀리프레시 모드로 끌어내림(잔상 누적 방어선).
    // interval=0이면 자동 트리거 비활성(사용자 명시 선택).
    // spread 모드에서는 한 번의 전환에 2쪽이 동시에 바뀌므로 카운터 가중 2배.
    val interval = current.fullRefreshInterval
    val triggerFull = if (interval > 0) {
      pagesSinceFullRefresh += if (current.spreadMode) 2 else 1
      (pagesSinceFullRefresh >= interval).also { hit ->
        if (hit) pagesSinceFullRefresh = 0
      }
    } else false

    _state.value = current.copy(
      currentPage = clamped,
      preloadWindow = preloadWindowFor(clamped),
      fullRefreshGeneration = if (triggerFull) current.fullRefreshGeneration + 1
      else current.fullRefreshGeneration,
    )
    triggerPreload()
  }

  /**
   * 풀리프레시 주기 변경. 앱 전역 설정에서 사용자 조정 시 호출.
   *
   * - 0: 자동 트리거 끔. [triggerFullRefresh]로만 동작
   * - 1: 매 페이지마다 (잔상 방어 최대, 깜빡임 빈도 ↑)
   * - 3 / 5 / 10 등: 잔상이 신경 쓰이는 기기에서 사용자 선택
   */
  fun setFullRefreshInterval(n: Int) {
    require(n >= 0) { "interval must be >= 0, got $n" }
    if (n == _state.value.fullRefreshInterval) return
    // 주기 변경 시 카운터를 리셋해 직전 카운트가 새 주기에 영향 주지 않게.
    pagesSinceFullRefresh = 0
    _state.value = _state.value.copy(fullRefreshInterval = n)
  }

  /**
   * 카운터 무관하게 즉시 풀리프레시 1회. 메뉴 "지금 풀리프레시" 버튼이 호출.
   * 카운터는 0으로 리셋되어 다음 자동 트리거는 N페이지 후로 미뤄진다.
   */
  fun triggerFullRefresh() {
    pagesSinceFullRefresh = 0
    _state.value = _state.value.copy(
      fullRefreshGeneration = _state.value.fullRefreshGeneration + 1,
    )
  }

  fun setDirection(direction: ReadingDirection) {
    if (direction == state.value.direction) return
    _state.value = _state.value.copy(direction = direction)
    _overrides.value = _overrides.value.copy(direction = direction)
  }

  fun setFitMode(fitMode: FitMode) {
    if (fitMode == state.value.fitMode) return
    _state.value = _state.value.copy(fitMode = fitMode)
    _overrides.value = _overrides.value.copy(fitMode = fitMode)
    triggerPreload()
  }

  /** 자동 여백 트리밍 on/off. fit과 직교 — fit 계산이 trim 결과를 입력으로 받아 사용. */
  fun setTrimEnabled(enabled: Boolean) {
    if (enabled == state.value.trimEnabled) return
    _state.value = _state.value.copy(trimEnabled = enabled)
    _overrides.value = _overrides.value.copy(trimEnabled = enabled)
    if (enabled) triggerPreload()
  }

  /**
   * 대비 배율 변경 — 0.5(50%)~2.0(200%). 1.0이면 원본.
   * 책별 저장은 다음 단계(M2-2). 지금은 세션 한정.
   */
  fun setContrast(value: Float) {
    require(value in ContrastMatrix.MIN..ContrastMatrix.MAX) {
      "contrast must be in [${ContrastMatrix.MIN}, ${ContrastMatrix.MAX}], got $value"
    }
    if (value == _state.value.contrast) return
    _state.value = _state.value.copy(contrast = value)
    _overrides.value = _overrides.value.copy(contrast = value)
  }

  /** 흑백 반전 on/off. ReaderView가 ColorMatrixColorFilter로 contrast와 결합 적용. */
  fun setInvertEnabled(enabled: Boolean) {
    if (enabled == _state.value.invertEnabled) return
    _state.value = _state.value.copy(invertEnabled = enabled)
  }

  /**
   * 두쪽 보기 on/off. on으로 켤 때 현재 페이지를 leading(짝수)로 정렬해 spread 경계에 맞춤.
   * off로 끌 때는 currentPage 그대로 유지(이미 그 페이지가 leading이었으니 단쪽으로 자연 노출).
   * v1.0의 fitMode/trim과 직교 — 각 spread 슬롯에서 FitCalculator가 절반 viewport로 다시 계산.
   */
  fun setSpreadMode(enabled: Boolean) {
    val current = _state.value
    if (enabled == current.spreadMode) return
    val newPage = if (enabled) alignToSpreadLeading(current.currentPage) else current.currentPage
    val pageChanged = newPage != current.currentPage
    _state.value = current.copy(
      spreadMode = enabled,
      currentPage = newPage,
      preloadWindow = if (pageChanged) preloadWindowFor(newPage) else current.preloadWindow,
    )
    _overrides.value = _overrides.value.copy(spreadMode = enabled)
    triggerPreload()
  }

  /** spread 모드 leading 정렬 — 짝수 인덱스로 내림(0,1→0 / 2,3→2 / 4,5→4). */
  private fun alignToSpreadLeading(pageIndex: Int): Int =
    if (pageIndex >= 0) (pageIndex / 2) * 2 else 0

  /**
   * 회전 잠금 변경. 본문 화면 진입 동안만 Activity에 적용되고, 화면을 떠나면
   * 호스트가 원복([io.github.sejoung.panelyink.reader.ui.ReaderOrientationEffect]).
   * 책별 저장은 ReaderState→BookSettings 매퍼 경로로 자동.
   */
  fun setOrientation(orientation: ReaderOrientation) {
    if (orientation == _state.value.orientation) return
    _state.value = _state.value.copy(orientation = orientation)
    _overrides.value = _overrides.value.copy(orientation = orientation)
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
    val generation = ++preloadGeneration
    preloadJob?.cancel()
    preloadJob = scope.launch {
      for (idx in s.preloadWindow.byProximityTo(s.currentPage)) {
        if (generation != preloadGeneration || !currentCoroutineContext().isActive) return@launch
        decoder.decode(idx, s.viewportWidth, s.viewportHeight, s.trimEnabled)
        if (generation != preloadGeneration || !currentCoroutineContext().isActive) return@launch
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

/**
 * ReaderViewModel이 외부로 노출하는 불변 상태 스냅샷.
 *
 * [fullRefreshGeneration]은 monotonic 증가 카운터다 — 값이 변경되면 ReaderView가
 * 검정 한 프레임으로 e-ink 풀리프레시를 트리거. UI는 값 그 자체가 아니라 "변경됨"만
 * 본다 (이전 값 비교는 LaunchedEffect의 key 처리에 위임).
 */
data class ReaderState(
  val currentPage: Int,
  val direction: ReadingDirection,
  val fitMode: FitMode,
  val preloadWindow: IntRange,
  val viewportWidth: Int = 0,
  val viewportHeight: Int = 0,
  val fullRefreshGeneration: Int = 0,
  /** 자동 여백 트리밍(M2) 적용 여부. ReaderView가 onDraw에서 참조. */
  val trimEnabled: Boolean = true,
  /** 자동 풀리프레시 주기(페이지 수). 0이면 자동 비활성. UI 토글이 변경. */
  val fullRefreshInterval: Int = DEFAULT_FULL_REFRESH_INTERVAL,
  /** 대비 배율(0.5..2.0). 1.0=원본. ReaderView가 ColorMatrixColorFilter로 적용. */
  val contrast: Float = ContrastMatrix.IDENTITY,
  /** 흑백 반전(macOS 다크모드 대체). contrast와 함께 적용 시 contrast → invert 순. */
  val invertEnabled: Boolean = false,
  /**
   * 두쪽 보기. v1.1. ReaderView가 viewport를 좌/우로 분할해 (currentPage, currentPage+1)을
   * direction에 따라 배치한다. currentPage는 leading(짝수)으로 정렬되어 들어옴.
   */
  val spreadMode: Boolean = false,
  /**
   * 회전 잠금. v1.1. ReaderScreen 라이프사이클 동안만 Activity.requestedOrientation에 반영하고
   * OS가 무시하면 [io.github.sejoung.panelyink.reader.ui.ReaderRotationLayout]이 Compose SW 회전으로 폴백.
   * 기본값 [ReaderOrientation.Portrait] = v1.0과 동일한 시각 동작.
   */
  val orientation: ReaderOrientation = ReaderOrientation.Portrait,
)

private fun IntRange.byProximityTo(center: Int): List<Int> {
  val list = toList()
  return list.sortedBy { kotlin.math.abs(it - center) }
}
