package io.github.sejoung.panelyink.reader.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Rect
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.graphics.Bitmap
import io.github.sejoung.panelyink.R
import io.github.sejoung.panelyink.core.fit.FitCalculator
import io.github.sejoung.panelyink.core.fit.FitMode
import io.github.sejoung.panelyink.core.preferences.ReadingDirection
import io.github.sejoung.panelyink.core.render.ContrastMatrix
import io.github.sejoung.panelyink.core.render.InvertMatrix
import io.github.sejoung.panelyink.reader.ReaderViewModel
import io.github.sejoung.panelyink.reader.session.CbzBookSession
import io.github.sejoung.panelyink.reader.spreadHasSecondary

/**
 * 본문 페이지 1장을 그리는 커스텀 View. PRD §8 — 뷰어 핫패스는 Compose가 아닌
 * View+Canvas로 분리해 invalidate 제어를 직접 한다.
 *
 * v1.0 책임:
 * - 현재 페이지 비트맵을 [FitCalculator] 결과로 src/dst 사각형 매핑해서 그림
 * - 캐시 미스 시 [Color.WHITE] (Paper) 빈 화면 — Guidelines §9 로딩 정책
 * - 사이즈 변경을 [ReaderViewModel.onViewportChanged] 로 통지
 *
 * 상태(현재 페이지/fit)는 외부 setter로 받음. Compose 레이어가 ViewModel 상태를
 * collect 해서 setPageIndex/setFitMode를 호출 → 단방향 흐름 유지.
 */
private const val TAG = "PanelyInk.ReaderView"

/**
 * 풀리프레시 시퀀스의 한 프레임이 화면에 머무는 시간(ms).
 *
 * e-ink 픽셀 변환은 ~150ms이므로 80ms는 짧지만, 시퀀스가 3프레임이라 누적 240ms로
 * 충분히 컨트롤러 풀리프레시 waveform을 트리거한다. 너무 길면 사용자가 체감하는
 * 페이지 흐름이 끊긴다(자동 트리거 케이스에서).
 */
private const val FULL_REFRESH_FRAME_HOLD_MS = 80L

private const val FAILED_PAGE_TEXT_SP = 16f

class ReaderView(context: Context) : View(context) {

  private var session: CbzBookSession? = null
  private var viewModel: ReaderViewModel? = null
  private var pageIndex: Int = 0
  private var fitMode: FitMode = FitMode.FitScreen
  private var trimEnabled: Boolean = true
  private var contrast: Float = ContrastMatrix.IDENTITY
  private var invertEnabled: Boolean = false
  private var spreadMode: Boolean = false
  private var coverAlone: Boolean = false
  private var direction: ReadingDirection = ReadingDirection.Ltr
  private var pageCount: Int = 0

  /**
   * 풀리프레시 시퀀스. [requestFullRefresh] 호출 시 채워지고, 매 onDraw에서
   * 1프레임씩 소비된다. 비워지면 다음 onDraw가 정상 콘텐츠를 그린다.
   *
   * 단일 검정 프레임은 일부 e-ink OEM 컨트롤러가 "큰 변화"로 인식하지 못하고
   * 그냥 다음 부분 갱신으로 합쳐버린다. 검정 → 흰색 → 검정 시퀀스로 픽셀 다수가
   * 두 번 반전되면 풀리프레시 waveform이 발화할 가능성이 훨씬 높다.
   */
  private val refreshSequence = ArrayDeque<Int>()

  /** 지금 화면에 머무는 시퀀스 프레임 색. null이면 시퀀스 비활성 또는 아직 첫 프레임 전. */
  private var refreshFrameColor: Int? = null

  /** [refreshFrameColor]를 다음 프레임으로 넘겨도 되는 시각([SystemClock.uptimeMillis]). */
  private var refreshFrameUntil: Long = 0L

  private val refreshTick = Runnable { invalidate() }

  private var failedPages: Set<Int> = emptySet()
  private val failedPageMessage: String = context.getString(R.string.reader_page_decode_failed)

  private val messagePaint = Paint().apply {
    isAntiAlias = true
    color = Color.BLACK
    textAlign = Paint.Align.CENTER
    textSize = FAILED_PAGE_TEXT_SP * context.resources.displayMetrics.scaledDensity
  }

  private val paint = Paint().apply {
    // e-ink + dithering 별도 단계(v1.5)에서 다룸. View 단계에선 fastest.
    isAntiAlias = false
    isFilterBitmap = true
    isDither = false
  }

  // drawSingle 핫패스에서 프레임/슬롯마다 새 Rect를 할당하지 않도록 재사용. onDraw는 단일 스레드.
  private val srcRect = Rect()
  private val dstRect = Rect()

  fun attach(session: CbzBookSession, viewModel: ReaderViewModel) {
    this.session = session
    this.viewModel = viewModel
    this.pageCount = viewModel.pageCount
    val s = viewModel.state.value
    this.pageIndex = s.currentPage
    this.fitMode = s.fitMode
    this.trimEnabled = s.trimEnabled
    this.contrast = s.contrast
    this.invertEnabled = s.invertEnabled
    this.spreadMode = s.spreadMode
    this.coverAlone = s.coverAlone
    this.direction = s.direction
    // contrast/invert 결합 colorFilter 적용은 applyColorAdjust 한 곳에서.
    applyColorAdjust()
    // 이미 측정된 상태였다면 바로 viewport 통지 (재바인딩 케이스).
    if (width > 0 && height > 0) {
      viewModel.onViewportChanged(width, height)
    }
    invalidate()
  }

  fun detach() {
    this.session = null
    this.viewModel = null
  }

  fun setPageIndex(index: Int) {
    if (this.pageIndex == index) return
    this.pageIndex = index
    // 사용자가 페이지를 넘겼는데 진행 중인 풀리프레시 시퀀스(검정/흰색 240ms)가 남아 있으면
    // 새 콘텐츠가 그만큼 늦게 화면에 도달한다. 시퀀스를 즉시 abort해 입력 응답성을 우선.
    abortFullRefresh()
    invalidate()
  }

  /**
   * [index] 페이지 디코드가 끝났다는 통지. 화면에 보이는 페이지일 때만 다시 그린다 —
   * 프리로드(±3)가 끝날 때마다 무조건 invalidate하면 안 바뀐 화면을 e-ink에 최대 6번 더 그리게 된다.
   */
  fun onPageDecoded(index: Int) {
    if (index == pageIndex || (index == pageIndex + 1 && hasSecondary())) invalidate()
  }

  fun setFailedPages(pages: Set<Int>) {
    if (this.failedPages == pages) return
    this.failedPages = pages
    invalidate()
  }

  private fun hasSecondary(): Boolean =
    spreadHasSecondary(spreadMode, coverAlone, pageIndex, pageCount)

  private fun abortFullRefresh() {
    refreshSequence.clear()
    refreshFrameColor = null
    removeCallbacks(refreshTick)
  }

  /** 시퀀스 진행용 invalidate는 항상 1개만 대기 — 끼어든 onDraw가 예약을 중복으로 쌓지 않게. */
  private fun scheduleRefreshTick(delayMs: Long) {
    removeCallbacks(refreshTick)
    postDelayed(refreshTick, delayMs)
  }

  fun setFitMode(mode: FitMode) {
    if (this.fitMode == mode) return
    this.fitMode = mode
    invalidate()
  }

  fun setTrimEnabled(enabled: Boolean) {
    if (this.trimEnabled == enabled) return
    this.trimEnabled = enabled
    invalidate()
  }

  fun setContrast(value: Float) {
    if (this.contrast == value) return
    this.contrast = value
    applyColorAdjust()
  }

  fun setInvertEnabled(enabled: Boolean) {
    if (this.invertEnabled == enabled) return
    this.invertEnabled = enabled
    applyColorAdjust()
  }

  fun setSpreadMode(enabled: Boolean) {
    if (this.spreadMode == enabled) return
    this.spreadMode = enabled
    invalidate()
  }

  fun setCoverAlone(enabled: Boolean) {
    if (this.coverAlone == enabled) return
    this.coverAlone = enabled
    // 두쪽 보기일 때만 시각적 영향(표지 단독/페어 정렬). 단쪽이면 다시 그릴 필요 없음.
    if (spreadMode) invalidate()
  }

  fun setDirection(dir: ReadingDirection) {
    if (this.direction == dir) return
    this.direction = dir
    // 두쪽 보기에서만 시각적으로 영향(좌/우 배치 반전). 단쪽일 때는 그릴 게 안 바뀌지만
    // 비용이 무시할 만하므로 분기 안 둠.
    if (spreadMode) invalidate()
  }

  /**
   * contrast와 invert를 결합한 ColorMatrixColorFilter를 paint에 적용.
   *
   * 결합 순서: contrast 먼저 적용 → 그 결과를 invert. ColorMatrix는 row-major 4×5
   * 행렬이고, [ColorMatrix.postConcat]은 `this = other × this` — 즉 postConcat에 넘긴
   * 행렬이 *나중에* 적용된다. 그래서 contrast 행렬에 invert를 postConcat.
   *
   * 둘 다 비활성이면 colorFilter=null로 비용 0.
   */
  private fun applyColorAdjust() {
    val needsContrast = contrast != ContrastMatrix.IDENTITY
    val needsInvert = invertEnabled
    paint.colorFilter = when {
      !needsContrast && !needsInvert -> null
      !needsContrast -> ColorMatrixColorFilter(InvertMatrix.build())
      !needsInvert -> ColorMatrixColorFilter(ContrastMatrix.build(contrast))
      else -> {
        val cm = ColorMatrix(ContrastMatrix.build(contrast))
        cm.postConcat(ColorMatrix(InvertMatrix.build()))
        ColorMatrixColorFilter(cm)
      }
    }
    invalidate()
  }

  /**
   * 풀리프레시 1회 트리거. 검정→흰색→검정 시퀀스 후 정상 콘텐츠로 복귀.
   * e-ink 컨트롤러가 큰 픽셀 변화를 감지해 풀리프레시 waveform 발화 가능성 ↑.
   */
  fun requestFullRefresh() {
    abortFullRefresh()
    refreshSequence.add(Color.BLACK)
    refreshSequence.add(Color.WHITE)
    refreshSequence.add(Color.BLACK)
    invalidate()
  }

  override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
    viewModel?.onViewportChanged(w, h)
  }

  @SuppressLint("DrawAllocation")
  override fun onDraw(canvas: Canvas) {
    // 풀리프레시 트릭: 한 프레임은 검정으로 칠하고, 다음 vsync에 정상 콘텐츠로
    // 다시 그린다. 표준 안드로이드 API에는 풀리프레시 강제가 없어, 큰 색차로
    // 컨트롤러를 끌어내리는 게 SDK 의존 없는 1차 방어선.
    //
    // 시퀀스는 onDraw 호출 횟수가 아니라 시각으로 진행한다. 프리로드 완료 같은 다른 invalidate가
    // 끼어들면 호출마다 한 프레임씩 넘어가 프레임당 ~16ms만 머물렀고(의도는 80ms), e-ink 응답 시간보다
    // 짧아 잔상 제거가 불안정했다. 유지 시간이 남은 동안의 onDraw는 같은 색을 다시 칠하기만 한다.
    val now = SystemClock.uptimeMillis()
    val holding = refreshFrameColor
    if (holding != null && now < refreshFrameUntil) {
      canvas.drawColor(holding)
      scheduleRefreshTick(refreshFrameUntil - now)
      return
    }
    if (refreshSequence.isNotEmpty()) {
      val color = refreshSequence.removeFirst()
      Log.d(
        TAG,
        "full refresh frame color=${"%08X".format(color)} remaining=${refreshSequence.size}",
      )
      refreshFrameColor = color
      refreshFrameUntil = now + FULL_REFRESH_FRAME_HOLD_MS
      canvas.drawColor(color)
      // 유지 시간이 끝나면 다음 프레임 색상(또는 시퀀스가 비었으면 정상 콘텐츠)으로 진행.
      scheduleRefreshTick(FULL_REFRESH_FRAME_HOLD_MS)
      return
    }
    refreshFrameColor = null
    canvas.drawColor(Color.WHITE)
    val s = session ?: return
    if (width <= 0 || height <= 0) return

    // 회전은 Compose 상위 레이어([io.github.sejoung.panelyink.reader.ui.ReaderRotationLayout])가
    // 전체 트리(페이지+오버레이)에 한 번 적용한다. ReaderView는 자기에게 주어진 view 차원만 본다.
    if (spreadMode) {
      drawSpread(canvas, s, width, height)
    } else {
      drawSingle(canvas, s, pageIndex, viewportX = 0, viewportWidth = width, viewportHeight = height)
    }
  }

  /**
   * 두쪽 그리기. logical viewport를 좌/우로 정확히 양분하고 leading=`pageIndex`, secondary=`pageIndex+1`을
   * direction에 따라 배치한다.
   * - LTR: 좌=leading, 우=secondary
   * - RTL: 좌=secondary, 우=leading
   * - 단독 슬롯(표지 한 장 단독 또는 secondary가 책 범위 밖인 홀수 마지막 한 장)은 절반-슬롯 대신
   *   전체 폭 중앙 정렬로 크게 그린다 — 두 단독 케이스가 일관되게 보이도록.
   *
   * 각 슬롯은 [drawSingle]을 절반 viewport로 호출 → 트리밍/contrast/invert가 단쪽과 동일 경로로 적용.
   *
   * 정렬 정책 — 좌측 페이지는 우측 정렬, 우측 페이지는 좌측 정렬로 그려 화면 중앙선에 두 페이지가 맞붙는다.
   * 페이지 비율이 절반 viewport보다 좁으면 중앙 정렬 시 가운데에 빈 여백이 생겨 spread 느낌이 깨지는 문제 해결.
   * 외부(좌측 페이지의 왼쪽 / 우측 페이지의 오른쪽)에 남는 여백은 사용자 시야 가장자리라 시각적으로 자연스럽다.
   */
  private fun drawSpread(canvas: Canvas, s: CbzBookSession, viewportWidth: Int, viewportHeight: Int) {
    val leading = pageIndex
    val secondary = pageIndex + 1
    // 단독 슬롯 — 표지 한 장 단독(coverAlone && 0쪽) 또는 secondary가 범위 밖(홀수 마지막 한 장).
    // 일관성을 위해 둘 다 전체 폭 중앙 정렬로 크게 그린다(절반-슬롯 + 빈 여백 대신). ViewModel도 이 경우 전체 해상도로 디코드.
    // 판정은 [spreadHasSecondary] 하나로 ViewModel과 공유.
    if (!hasSecondary()) {
      if (leading in 0 until pageCount) {
        drawSingle(canvas, s, leading, viewportX = 0, viewportWidth = viewportWidth, viewportHeight = viewportHeight)
      }
      return
    }

    val half = viewportWidth / 2
    val rightStart = half // 홀수 width 1px은 우측 슬롯이 흡수
    val (leftPage, rightPage) = when (direction) {
      ReadingDirection.Rtl -> secondary to leading
      else -> leading to secondary
    }
    if (leftPage in 0 until pageCount) {
      drawSingle(
        canvas, s, leftPage,
        viewportX = 0,
        viewportWidth = half,
        viewportHeight = viewportHeight,
        align = HorizontalAlign.End,
      )
    }
    if (rightPage in 0 until pageCount) {
      drawSingle(
        canvas, s, rightPage,
        viewportX = rightStart,
        viewportWidth = viewportWidth - rightStart,
        viewportHeight = viewportHeight,
        align = HorizontalAlign.Start,
      )
    }
  }

  /**
   * 비트맵 1장을 지정된 logical viewport rect에 fit하여 그림. canvas 변환은 호출자가 이미 적용.
   *
   * [align]은 페이지가 viewportWidth보다 좁을 때 가로 배치 결정:
   * - [HorizontalAlign.Center]: 단쪽 모드 기본 — 좌우 균등 여백
   * - [HorizontalAlign.Start] / [HorizontalAlign.End]: spread 모드 — 한쪽 정렬해 가운데 맞붙임
   */
  private fun drawSingle(
    canvas: Canvas,
    s: CbzBookSession,
    index: Int,
    viewportX: Int,
    viewportWidth: Int,
    viewportHeight: Int,
    align: HorizontalAlign = HorizontalAlign.Center,
  ) {
    if (viewportWidth <= 0 || viewportHeight <= 0) return
    val bitmap: Bitmap? = s.pageBitmap(index)
    if (bitmap == null) {
      // 디코드 실패 페이지는 빈 화면 대신 안내 — 아직 디코드 중인 페이지는 기존대로 Paper.
      if (index in failedPages) {
        canvas.drawText(
          failedPageMessage,
          viewportX + viewportWidth / 2f,
          viewportHeight / 2f,
          messagePaint,
        )
      }
      return
    }
    val trim = if (trimEnabled) s.pageTrim(index, bitmap) else null
    val fit = FitCalculator.compute(
      pageWidth = bitmap.width,
      pageHeight = bitmap.height,
      viewportWidth = viewportWidth,
      viewportHeight = viewportHeight,
      mode = fitMode,
      trim = trim,
    )
    srcRect.set(
      fit.srcX,
      fit.srcY,
      fit.srcX + fit.srcWidth,
      fit.srcY + fit.srcHeight,
    )
    val dstX = viewportX + when (align) {
      HorizontalAlign.Start -> 0
      HorizontalAlign.Center -> fit.offsetX
      HorizontalAlign.End -> viewportWidth - fit.drawWidth
    }
    dstRect.set(
      dstX,
      fit.offsetY,
      dstX + fit.drawWidth,
      fit.offsetY + fit.drawHeight,
    )
    canvas.drawBitmap(bitmap, srcRect, dstRect, paint)
  }

  private enum class HorizontalAlign { Start, Center, End }
}
