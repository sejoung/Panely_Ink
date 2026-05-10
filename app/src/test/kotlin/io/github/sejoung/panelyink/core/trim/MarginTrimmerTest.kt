package io.github.sejoung.panelyink.core.trim

import io.github.sejoung.panelyink.core.fit.TrimRect
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `MarginTrimmer` 알고리즘 검증. Bitmap 의존 없이 IntArray + width/height로만 검증.
 *
 * ARGB int 도우미: white = 0xFFFFFFFF, black = 0xFF000000 (불투명 검정).
 */
class MarginTrimmerTest {

    private val W = 0xFFFFFFFF.toInt()
    private val K = 0xFF000000.toInt()

    /**
     * width × height 크기의 흰 캔버스에 검정 사각형을 그려 픽셀 배열을 만든다.
     */
    private fun canvas(
        width: Int,
        height: Int,
        rectX: Int,
        rectY: Int,
        rectW: Int,
        rectH: Int,
    ): IntArray {
        val pixels = IntArray(width * height) { W }
        for (y in rectY until rectY + rectH) {
            for (x in rectX until rectX + rectW) {
                pixels[y * width + x] = K
            }
        }
        return pixels
    }

    @Test
    fun centerRectIsDetected() {
        // 100x100 캔버스 가운데 60x60 검정 → 본문 = (20, 20, 60, 60)
        val pixels = canvas(100, 100, rectX = 20, rectY = 20, rectW = 60, rectH = 60)
        val trim = MarginTrimmer.detect(pixels, 100, 100)
        assertEquals(TrimRect(20, 20, 60, 60), trim)
    }

    @Test
    fun allWhiteReturnsOriginal() {
        // 모두 흰색 → 트리밍할 게 없음. 원본 그대로 반환.
        val pixels = IntArray(50 * 50) { W }
        val trim = MarginTrimmer.detect(pixels, 50, 50)
        assertEquals(TrimRect(0, 0, 50, 50), trim)
    }

    @Test
    fun safetyGuardWhenContentTooSmall() {
        // 본문이 원본 대비 4% (4x4)라 안전 가드(<30%) 발동 → 원본 그대로
        val pixels = canvas(100, 100, rectX = 48, rectY = 48, rectW = 4, rectH = 4)
        val trim = MarginTrimmer.detect(pixels, 100, 100)
        assertEquals(TrimRect(0, 0, 100, 100), trim)
    }

    @Test
    fun leftMarginOnly() {
        // 왼쪽 30픽셀이 흰 여백, 나머지(70픽셀)는 본문 → x=30, width=70, 세로는 전체
        val pixels = canvas(100, 100, rectX = 30, rectY = 0, rectW = 70, rectH = 100)
        val trim = MarginTrimmer.detect(pixels, 100, 100)
        assertEquals(TrimRect(30, 0, 70, 100), trim)
    }

    @Test
    fun fullCanvasContent() {
        // 전체가 본문(검정) → 흰 행이 없으니 그대로
        val pixels = IntArray(50 * 50) { K }
        val trim = MarginTrimmer.detect(pixels, 50, 50)
        assertEquals(TrimRect(0, 0, 50, 50), trim)
    }

    @Test
    fun rowFractionToleratesNoise() {
        // 100x100 흰 캔버스에 노이즈 픽셀 1개(99% 흰색이지만 1%는 검정).
        // 기본 rowFraction=0.95라 그 행도 "흰 여백"으로 간주 → 원본 반환(다른 행은 모두 흰색).
        val pixels = IntArray(100 * 100) { W }
        pixels[5 * 100 + 50] = K // (50, 5)에 점 1개
        val trim = MarginTrimmer.detect(pixels, 100, 100)
        assertEquals(TrimRect(0, 0, 100, 100), trim)
    }

    @Test
    fun thresholdCanBeAdjusted() {
        // 회색 배경(밝기 200)이 흰색 임계값 240보다 어두워, 기본 설정으론 본문으로 인식.
        // threshold를 180으로 낮추면 회색을 흰색 취급해 본문이 감지됨.
        val gray = (0xFF shl 24) or (200 shl 16) or (200 shl 8) or 200
        val pixels = IntArray(50 * 50) { gray }
        // 가운데 25x25에 검정
        for (y in 12 until 37) for (x in 12 until 37) {
            pixels[y * 50 + x] = K
        }
        val withDefault = MarginTrimmer.detect(pixels, 50, 50)
        // default threshold=240이면 회색도 본문으로 → 트리밍 X
        assertEquals(TrimRect(0, 0, 50, 50), withDefault)

        val withLowered = MarginTrimmer.detect(
            pixels, 50, 50,
            whitenessThreshold = 180,
        )
        assertEquals(TrimRect(12, 12, 25, 25), withLowered)
    }

    // -------------------------------
    // rowProvider 변형 (메모리 효율 API)
    // -------------------------------

    /** [pixels]에서 한 행을 buffer로 복사하는 row provider — Bitmap.getPixels의 JVM 모방. */
    private fun rowProviderFor(pixels: IntArray, width: Int): (Int, IntArray) -> Unit =
        { y, buffer -> System.arraycopy(pixels, y * width, buffer, 0, width) }

    @Test
    fun rowProvider_centerRectMatchesIntArrayApi() {
        val pixels = canvas(100, 100, rectX = 20, rectY = 20, rectW = 60, rectH = 60)
        val viaProvider = MarginTrimmer.detect(
            rowProvider = rowProviderFor(pixels, 100),
            width = 100,
            height = 100,
        )
        val viaArray = MarginTrimmer.detect(pixels, 100, 100)
        assertEquals("rowProvider must match IntArray API", viaArray, viaProvider)
        assertEquals(TrimRect(20, 20, 60, 60), viaProvider)
    }

    @Test
    fun rowProvider_allWhiteReturnsOriginal() {
        val pixels = IntArray(50 * 50) { W }
        val trim = MarginTrimmer.detect(
            rowProvider = rowProviderFor(pixels, 50),
            width = 50,
            height = 50,
        )
        assertEquals(TrimRect(0, 0, 50, 50), trim)
    }

    @Test
    fun rowProvider_leftMarginOnly() {
        val pixels = canvas(100, 100, rectX = 30, rectY = 0, rectW = 70, rectH = 100)
        val trim = MarginTrimmer.detect(
            rowProvider = rowProviderFor(pixels, 100),
            width = 100,
            height = 100,
        )
        assertEquals(TrimRect(30, 0, 70, 100), trim)
    }

    @Test
    fun rowProvider_neverAllocatesFullPixelArray() {
        // 메모리 효율 변형의 핵심 약속 — 호출자는 width 길이의 buffer 1개만 받는다.
        // provider가 받은 buffer의 size가 항상 width인지 확인.
        val pixels = canvas(64, 64, rectX = 8, rectY = 8, rectW = 48, rectH = 48)
        val seenBufferSizes = mutableSetOf<Int>()
        MarginTrimmer.detect(
            rowProvider = { y, buffer ->
                seenBufferSizes += buffer.size
                System.arraycopy(pixels, y * 64, buffer, 0, 64)
            },
            width = 64,
            height = 64,
        )
        // 모든 호출이 동일한 width 길이 buffer를 사용
        assertEquals(setOf(64), seenBufferSizes)
    }

    @Test
    fun rowProvider_calledIdempotentlyForSameRowAcrossPhases() {
        // 컬럼 스캔 단계가 row를 다시 읽기 때문에 같은 y가 두 번 호출될 수 있다.
        // provider가 idempotent해야 한다는 계약을 명시적으로 검증.
        val pixels = canvas(40, 40, rectX = 10, rectY = 10, rectW = 20, rectH = 20)
        val callsByY = mutableMapOf<Int, Int>()
        val trim = MarginTrimmer.detect(
            rowProvider = { y, buffer ->
                callsByY.merge(y, 1, Int::plus)
                System.arraycopy(pixels, y * 40, buffer, 0, 40)
            },
            width = 40,
            height = 40,
        )
        assertEquals(TrimRect(10, 10, 20, 20), trim)
        // body 행(10..29)은 row scan + column scan 두 번 호출되는 것이 정상.
        // 이 테스트의 목적은 idempotency 위반(예: 캐싱 누락) 시 결과가 망가지지 않는지 검증.
        // 위에서 결과가 정확함이 이미 확인됨.
        kotlin.check(callsByY.values.any { it >= 2 }) {
            "expected at least one row to be visited multiple times for column scan"
        }
    }
}
