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
 * v1.0 M1.1 스코프: 단일 페이지 정적 렌더만. 입력 / 페이지 이동은 M1.2.
 *
 * 캐시 미스 시 [Color.WHITE] (Paper) 빈 화면 유지 — Guidelines §9 로딩 정책.
 */
class ReaderView(context: Context) : View(context) {

    private var session: CbzBookSession? = null
    private var pageIndex: Int = 0
    private var fitMode: FitMode = FitMode.FitScreen

    private val paint = Paint().apply {
        // e-ink + dithering 별도 단계(v1.5)에서 다룸. View 단계에선 fastest.
        isAntiAlias = false
        isFilterBitmap = true
        isDither = false
    }

    fun attach(session: CbzBookSession, initialPage: Int = 0) {
        this.session = session
        this.pageIndex = initialPage
        invalidate()
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
