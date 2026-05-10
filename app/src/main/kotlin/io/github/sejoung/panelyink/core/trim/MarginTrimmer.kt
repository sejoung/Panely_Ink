package io.github.sejoung.panelyink.core.trim

import io.github.sejoung.panelyink.core.fit.TrimRect

/**
 * 페이지 비트맵의 흰 여백을 감지해 본문 직사각형을 [TrimRect]로 반환한다 — PRD §6.1
 * "자동 여백 트리밍"의 1차 알고리즘. 7" e-ink에서 가독성 향상 효과가 가장 큰 항목.
 *
 * 입력은 ARGB 8888 픽셀 배열 + 크기 — 안드로이드 [android.graphics.Bitmap]에 의존하지
 * 않게 분리해 JVM에서 단위 테스트 가능. Bitmap → IntArray 변환은 호출자(어댑터) 책임.
 *
 * 알고리즘:
 * 1. 위/아래/왼/오 각각 "흰색 비율이 [rowFraction] 이상"인 행/열을 안쪽으로 스킵
 * 2. 결과 폭/높이가 너무 작으면(원본 대비 [minRatio] 미만) 트리밍을 포기하고 원본 반환
 *    — 한 페이지 전체가 흰색이거나 알고리즘이 잘못 인식한 경우의 안전가드
 *
 * 임계값 기본은 7" 흑백 만화 스캔본 기준으로 잡았다. 컨텐츠가 회색조 배경(웹툰 일부)
 * 같이 흰색이 적은 케이스에선 [whitenessThreshold]를 낮춰 호출자가 조정.
 */
object MarginTrimmer {

    /** 픽셀이 "흰색"으로 간주되는 평균 brightness 임계값(0..255). */
    const val DEFAULT_WHITENESS_THRESHOLD = 240

    /** 행/열이 "흰 여백"으로 간주되는 흰 픽셀 비율(0..1). */
    const val DEFAULT_ROW_FRACTION = 0.95f

    /** 결과 폭/높이가 원본 대비 이 비율 미만이면 트리밍 포기. */
    const val DEFAULT_MIN_RATIO = 0.30f

    /**
     * @param pixels 행 우선(row-major) ARGB int 배열. 길이는 width*height
     * @param width 픽셀 가로 크기
     * @param height 픽셀 세로 크기
     */
    fun detect(
        pixels: IntArray,
        width: Int,
        height: Int,
        whitenessThreshold: Int = DEFAULT_WHITENESS_THRESHOLD,
        rowFraction: Float = DEFAULT_ROW_FRACTION,
        minRatio: Float = DEFAULT_MIN_RATIO,
    ): TrimRect {
        require(width > 0 && height > 0) { "size must be positive" }
        require(pixels.size >= width * height) { "pixel array smaller than width*height" }
        return detect(
            rowProvider = { y, buffer ->
                val rowOffset = y * width
                System.arraycopy(pixels, rowOffset, buffer, 0, width)
            },
            width = width,
            height = height,
            whitenessThreshold = whitenessThreshold,
            rowFraction = rowFraction,
            minRatio = minRatio,
        )
    }

    /**
     * 메모리 효율 변형 — 호출자가 한 행씩 [rowProvider]로 채워준다. 풀 비트맵 픽셀 배열을
     * 미리 만들 필요가 없다(2000×3000 페이지 기준 24MB → 8KB 행 버퍼).
     *
     * [rowProvider]는 `(y, buffer)` 형태로 호출되며 buffer 길이는 항상 [width]. 같은 y로
     * 두 번 호출될 수 있으므로(컬럼 스캔 단계) idempotent해야 한다.
     */
    fun detect(
        rowProvider: (y: Int, buffer: IntArray) -> Unit,
        width: Int,
        height: Int,
        whitenessThreshold: Int = DEFAULT_WHITENESS_THRESHOLD,
        rowFraction: Float = DEFAULT_ROW_FRACTION,
        minRatio: Float = DEFAULT_MIN_RATIO,
    ): TrimRect {
        require(width > 0 && height > 0) { "size must be positive" }
        val rowBuffer = IntArray(width)

        // 위에서 아래로, 흰 행을 스킵
        var top = 0
        while (top < height) {
            rowProvider(top, rowBuffer)
            if (!isRowWhite(rowBuffer, width, whitenessThreshold, rowFraction)) break
            top++
        }
        // 모든 행이 흰색 → 트리밍 포기
        if (top >= height) return TrimRect(0, 0, width, height)

        // 아래에서 위로
        var bottom = height - 1
        while (bottom > top) {
            rowProvider(bottom, rowBuffer)
            if (!isRowWhite(rowBuffer, width, whitenessThreshold, rowFraction)) break
            bottom--
        }

        // 컬럼 스캔: top..bottom 행을 한 번 더 훑으며 각 x에 대해 흰 픽셀 수 카운트.
        // 하나의 행 버퍼를 재사용 — 메모리는 여전히 IntArray(width).
        val rowCount = bottom - top + 1
        val whiteCountPerColumn = IntArray(width)
        for (y in top..bottom) {
            rowProvider(y, rowBuffer)
            for (x in 0 until width) {
                if (isWhite(rowBuffer[x], whitenessThreshold)) {
                    whiteCountPerColumn[x]++
                }
            }
        }

        var left = 0
        while (left < width &&
            whiteCountPerColumn[left].toFloat() / rowCount >= rowFraction
        ) {
            left++
        }
        if (left >= width) return TrimRect(0, 0, width, height)

        var right = width - 1
        while (right > left &&
            whiteCountPerColumn[right].toFloat() / rowCount >= rowFraction
        ) {
            right--
        }

        val resultW = right - left + 1
        val resultH = bottom - top + 1
        // 안전가드: 본문이 비정상적으로 작으면 원본을 그대로 둔다
        if (resultW.toFloat() / width < minRatio || resultH.toFloat() / height < minRatio) {
            return TrimRect(0, 0, width, height)
        }
        return TrimRect(left, top, resultW, resultH)
    }

    private fun isRowWhite(
        rowBuffer: IntArray,
        width: Int,
        threshold: Int,
        rowFraction: Float,
    ): Boolean {
        var whites = 0
        for (x in 0 until width) {
            if (isWhite(rowBuffer[x], threshold)) whites++
        }
        return whites.toFloat() / width >= rowFraction
    }

    /** ARGB int에서 평균 brightness가 [threshold] 이상이면 흰색 취급. */
    private fun isWhite(argb: Int, threshold: Int): Boolean {
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        return (r + g + b) / 3 >= threshold
    }
}
