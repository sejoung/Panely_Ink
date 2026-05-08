package io.github.sejoung.panelyink.core.render

/**
 * 흑백 반전(invert) 변환을 4×5 ColorMatrix 행렬(20개 float)로 표현 — PRD §6.1
 * "Invert (블랙/화이트 반전) — macOS의 다크모드 대체".
 *
 * 수식: out = 255 - in  (R, G, B 각각). alpha는 항등.
 *
 * 안드로이드 의존이 없도록 [FloatArray]만 노출 — 호출자(`ReaderView`)가
 * `ColorMatrixColorFilter`를 만든다. JVM 단위 테스트 가능.
 */
object InvertMatrix {

    /** 4×5 ColorMatrix를 row-major FloatArray(20개)로 반환. */
    fun build(): FloatArray = floatArrayOf(
        -1f, 0f, 0f, 0f, 255f,
        0f, -1f, 0f, 0f, 255f,
        0f, 0f, -1f, 0f, 255f,
        0f, 0f, 0f, 1f, 0f,
    )
}
