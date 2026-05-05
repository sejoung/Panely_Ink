package io.github.sejoung.panelyink.reader

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
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
class ReaderView(context: Context) : View(context) {

    private var session: CbzBookSession? = null
    private var viewModel: ReaderViewModel? = null
    private var pageIndex: Int = 0
    private var fitMode: FitMode = FitMode.FitScreen

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

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        viewModel?.onViewportChanged(w, h)
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.WHITE)
        val s = session ?: return
        if (width <= 0 || height <= 0) return
        val bitmap = s.pageBitmap(pageIndex) ?: return
        val fit = FitCalculator.compute(
            pageWidth = bitmap.width,
            pageHeight = bitmap.height,
            viewportWidth = width,
            viewportHeight = height,
            mode = fitMode,
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
