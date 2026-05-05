package io.github.sejoung.panelyink.core.fit

/**
 * 페이지 비트맵을 뷰포트에 어떻게 그릴지 계산하는 순수 함수.
 *
 * 자동 트리밍이 켜진 경우 (PRD §6.1 e-ink 최적화) [trim]에 트리밍된 직사각형의
 * 원본 좌표계 크기를 넘기면 그것을 기준으로 fit이 계산된다.
 *
 * 출력 [FitResult]는 dp가 아닌 픽셀 단위. 호출자가 그릴 때 그대로 사용한다.
 */
object FitCalculator {

    fun compute(
        pageWidth: Int,
        pageHeight: Int,
        viewportWidth: Int,
        viewportHeight: Int,
        mode: FitMode,
        trim: TrimRect? = null,
    ): FitResult {
        require(pageWidth > 0 && pageHeight > 0) { "page size must be positive" }
        require(viewportWidth > 0 && viewportHeight > 0) { "viewport size must be positive" }

        val srcW = trim?.width ?: pageWidth
        val srcH = trim?.height ?: pageHeight
        val srcX = trim?.x ?: 0
        val srcY = trim?.y ?: 0

        val scale = when (mode) {
            FitMode.FitScreen -> minOf(
                viewportWidth.toFloat() / srcW,
                viewportHeight.toFloat() / srcH,
            )
            FitMode.FitWidth -> viewportWidth.toFloat() / srcW
            FitMode.FitHeight -> viewportHeight.toFloat() / srcH
            is FitMode.Zoom -> mode.factor
        }

        val drawW = (srcW * scale).toInt().coerceAtLeast(1)
        val drawH = (srcH * scale).toInt().coerceAtLeast(1)
        val offsetX = ((viewportWidth - drawW) / 2f).toInt()
        val offsetY = ((viewportHeight - drawH) / 2f).toInt()

        return FitResult(
            srcX = srcX,
            srcY = srcY,
            srcWidth = srcW,
            srcHeight = srcH,
            drawWidth = drawW,
            drawHeight = drawH,
            offsetX = offsetX,
            offsetY = offsetY,
            scale = scale,
        )
    }
}

/** 자동 트리밍 결과. 원본 비트맵 좌표계의 직사각형. */
data class TrimRect(val x: Int, val y: Int, val width: Int, val height: Int) {
    init {
        require(x >= 0 && y >= 0 && width > 0 && height > 0) { "invalid trim rect" }
    }
}

/** [FitCalculator.compute] 출력. 모든 값은 픽셀. */
data class FitResult(
    val srcX: Int,
    val srcY: Int,
    val srcWidth: Int,
    val srcHeight: Int,
    val drawWidth: Int,
    val drawHeight: Int,
    val offsetX: Int,
    val offsetY: Int,
    val scale: Float,
)
