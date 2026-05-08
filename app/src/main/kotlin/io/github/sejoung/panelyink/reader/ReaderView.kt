package io.github.sejoung.panelyink.reader

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.util.Log
import android.view.View
import io.github.sejoung.panelyink.core.fit.FitCalculator
import io.github.sejoung.panelyink.core.fit.FitMode

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

class ReaderView(context: Context) : View(context) {

    private var session: CbzBookSession? = null
    private var viewModel: ReaderViewModel? = null
    private var pageIndex: Int = 0
    private var fitMode: FitMode = FitMode.FitScreen
    private var trimEnabled: Boolean = true

    /**
     * 풀리프레시 시퀀스. [requestFullRefresh] 호출 시 채워지고, 매 onDraw에서
     * 1프레임씩 소비된다. 비워지면 다음 onDraw가 정상 콘텐츠를 그린다.
     *
     * 단일 검정 프레임은 일부 e-ink OEM 컨트롤러가 "큰 변화"로 인식하지 못하고
     * 그냥 다음 부분 갱신으로 합쳐버린다. 검정 → 흰색 → 검정 시퀀스로 픽셀 다수가
     * 두 번 반전되면 풀리프레시 waveform이 발화할 가능성이 훨씬 높다.
     */
    private val refreshSequence = ArrayDeque<Int>()

    private val paint = Paint().apply {
        // e-ink + dithering 별도 단계(v1.5)에서 다룸. View 단계에선 fastest.
        isAntiAlias = false
        isFilterBitmap = true
        isDither = false
    }

    fun attach(session: CbzBookSession, viewModel: ReaderViewModel) {
        this.session = session
        this.viewModel = viewModel
        this.pageIndex = viewModel.state.value.currentPage
        this.fitMode = viewModel.state.value.fitMode
        this.trimEnabled = viewModel.state.value.trimEnabled
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
        invalidate()
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

    /**
     * 풀리프레시 1회 트리거. 검정→흰색→검정 시퀀스 후 정상 콘텐츠로 복귀.
     * e-ink 컨트롤러가 큰 픽셀 변화를 감지해 풀리프레시 waveform 발화 가능성 ↑.
     */
    fun requestFullRefresh() {
        refreshSequence.clear()
        refreshSequence.add(Color.BLACK)
        refreshSequence.add(Color.WHITE)
        refreshSequence.add(Color.BLACK)
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        viewModel?.onViewportChanged(w, h)
    }

    override fun onDraw(canvas: Canvas) {
        // 풀리프레시 트릭: 한 프레임은 검정으로 칠하고, 다음 vsync에 정상 콘텐츠로
        // 다시 그린다. 표준 안드로이드 API에는 풀리프레시 강제가 없어, 큰 색차로
        // 컨트롤러를 끌어내리는 게 SDK 의존 없는 1차 방어선.
        if (refreshSequence.isNotEmpty()) {
            val color = refreshSequence.removeFirst()
            Log.d(
                TAG,
                "full refresh frame color=${"%08X".format(color)} remaining=${refreshSequence.size}",
            )
            canvas.drawColor(color)
            // 다음 프레임 색상으로 또 invalidate. 시퀀스가 비면 정상 onDraw 진입.
            postInvalidateDelayed(FULL_REFRESH_FRAME_HOLD_MS)
            return
        }
        canvas.drawColor(Color.WHITE)
        val s = session ?: return
        if (width <= 0 || height <= 0) return
        val bitmap = s.pageBitmap(pageIndex) ?: return
        val trim = if (trimEnabled) s.pageTrim(pageIndex) else null
        val fit = FitCalculator.compute(
            pageWidth = bitmap.width,
            pageHeight = bitmap.height,
            viewportWidth = width,
            viewportHeight = height,
            mode = fitMode,
            trim = trim,
        )
        val src = Rect(
            fit.srcX,
            fit.srcY,
            fit.srcX + fit.srcWidth,
            fit.srcY + fit.srcHeight,
        )
        val dst = Rect(
            fit.offsetX,
            fit.offsetY,
            fit.offsetX + fit.drawWidth,
            fit.offsetY + fit.drawHeight,
        )
        canvas.drawBitmap(bitmap, src, dst, paint)
    }
}
