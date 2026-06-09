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
import kotlinx.coroutines.coroutineScope
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
    // 정렬 → 클램프 → 재정렬 순. 짝수 페이지 책(예: 8쪽)의 마지막 spread(6·7)에서 goNext가
    // align(8)=8 → clamp(7)로 홀수에 떨어지면 leading이 미정렬되어 마지막 페이지가 단독 노출되는
    // 버그가 있었다. 클램프 결과를 한 번 더 정렬해 항상 leading(짝수)에 안착시킨다.
    val aligned = if (current.spreadMode) alignToSpreadLeading(pageIndex) else pageIndex
    val clamped = aligned.coerceIn(0, pageCount - 1).let {
      if (current.spreadMode) alignToSpreadLeading(it) else it
    }
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
   * 책별 저장은 [overrides]가 변경 필드를 기록 → [io.github.sejoung.panelyink.reader.ui.ReaderPersistenceEffects]가 영속화.
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
    // spread 모드는 화면에 2쪽이 동시에 보이므로 leading(N)과 secondary(N+1) 둘 다 최우선 디코드.
    // 기존 단일-중심 proximity는 N→N-1→N+1 순이라 페이지 막 넘긴 직후 첫 디코드 1회 동안 우측이 비는 문제 발생.
    val decodeCenters = if (s.spreadMode && s.currentPage + 1 <= pageCount - 1) {
      intArrayOf(s.currentPage, s.currentPage + 1)
    } else {
      intArrayOf(s.currentPage)
    }
    // spread 모드는 각 페이지가 화면 절반에만 그려지므로 디코드 해상도도 절반으로 충분.
    // → inSampleSize가 한 단계 더 줄여 메모리/디코드시간 절반, cache eviction 압박 감소.
    val decodeViewportW = if (s.spreadMode) s.viewportWidth / 2 else s.viewportWidth
    val decodeViewportH = s.viewportHeight
    preloadJob = scope.launch {
      val visibleSet = decodeCenters.toHashSet()
      // Phase 1: visible 페어를 **병렬** 디코드 후 emit 1회 — invalidate 1회만 발생.
      // [io.github.sejoung.panelyink.core.archive.CbzArchive]가 dup PFD로 reader pool을 유지하므로
      // 두 페이지가 독립된 ZipFile/Channel을 통해 동시에 디코드된다. spread 모드 진입/페이지 넘김에서
      // 두 페이지가 사용자 시야에 동시 등장 (sequential 디코드의 0-200ms 한쪽만 보이는 깜빡임 제거).
      coroutineScope {
        decodeCenters.forEach { idx ->
          launch {
            // 새 페이지를 캐시에 넣기 직전에 visible 페이지의 LRU 위치를 갱신해 evict 보호.
            // 풀리프레시 시퀀스 중에는 onDraw가 pageBitmap을 호출하지 않아 LRU touch가 자연 발생 안 함.
            decoder.keepWarm(decodeCenters)
            decoder.decode(idx, decodeViewportW, decodeViewportH, s.trimEnabled)
          }
        }
      } // coroutineScope가 모든 자식 launch를 await — 둘 다 cache에 들어간 후에야 다음 줄로 넘어감.
      if (generation != preloadGeneration || !currentCoroutineContext().isActive) return@launch
      // emit은 1회 — onDraw가 invalidate 받아 visible 페어 둘 다 cache에서 그린다. 인덱스 값 자체는 collector가 무시.
      _decoded.emit(decodeCenters[0])

      // Phase 2: 나머지 인접 페이지는 sequential — cache 압박 줄이고 디스크 IO 분산.
      // 디코드되는 대로 emit해 다음 페이지 넘김에 대비.
      for (idx in s.preloadWindow.byProximityTo(*decodeCenters)) {
        if (idx in visibleSet) continue
        if (generation != preloadGeneration || !currentCoroutineContext().isActive) return@launch
        decoder.keepWarm(decodeCenters)
        decoder.decode(idx, decodeViewportW, decodeViewportH, s.trimEnabled)
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

/**
 * 여러 중심점 중 최단 거리 기준으로 정렬. spread 모드처럼 화면에 동시에 보이는 페이지가 2개일 때
 * 둘 다 거리 0으로 묶여 1순위로 디코드되도록.
 *
 * 동일 거리에서 안정 정렬 — `IntRange.toList()`가 오름차순을 보장하므로 같은 거리면 작은 인덱스 먼저.
 */
private fun IntRange.byProximityTo(vararg centers: Int): List<Int> {
  require(centers.isNotEmpty()) { "centers must not be empty" }
  val list = toList()
  return list.sortedBy { idx -> centers.minOf { kotlin.math.abs(idx - it) } }
}
