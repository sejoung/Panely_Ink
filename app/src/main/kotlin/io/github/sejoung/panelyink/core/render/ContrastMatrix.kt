package io.github.sejoung.panelyink.core.render

/**
 * 대비(contrast) 변환을 [android.graphics.ColorMatrix] 4×5 행렬로 표현 — PRD §6.1
 * "Contrast / Gamma" 중 contrast 부분.
 *
 * 수식: out = (in - 127.5) × s + 127.5  ⇒  s·in + (1 - s)×127.5
 * - s = 1.0 → 항등(원본). 행렬은 identity, 평행이동 t = 0
 * - s > 1.0 → 어두운 픽셀은 더 어둡고 밝은 픽셀은 더 밝게
 * - s < 1.0 → 모든 색이 회색에 가까워짐
 *
 * 안드로이드 의존이 없도록 [FloatArray]만 반환 — 호출자(`ReaderView`)가 그 배열로
 * `ColorMatrixColorFilter`를 만든다. JVM 단위 테스트 가능.
 */
object ContrastMatrix {

    /** 사용 가능한 대비 배율 하한(50%). */
    const val MIN = 0.5f

    /** 사용 가능한 대비 배율 상한(200%). */
    const val MAX = 2.0f

    /** 변환 없음(원본). */
    const val IDENTITY = 1.0f

    /**
     * 4×5 ColorMatrix 행렬을 row-major FloatArray(20개)로 반환.
     *
     * 알파 채널은 항등 — 색상 채널만 대비 변환.
     */
    fun build(contrast: Float): FloatArray {
        require(contrast > 0f) { "contrast must be positive, got $contrast" }
        val s = contrast
        val t = (1f - s) * 127.5f
        return floatArrayOf(
            s, 0f, 0f, 0f, t,
            0f, s, 0f, 0f, t,
            0f, 0f, s, 0f, t,
            0f, 0f, 0f, 1f, 0f,
        )
    }
}
