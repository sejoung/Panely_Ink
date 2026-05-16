package io.github.sejoung.panelyink.reader.session

/**
 * 페이지 1장을 비트맵으로 디코드하는 추상 인터페이스.
 *
 * v1.0 결정 (PRD §8): RK3566 CPU 한계 때문에 NDK libjpeg-turbo / libwebp 직접 호출이
 * 사실상 필수다. 이 인터페이스 뒤에 NDK 디코더가 들어갈 자리만 비워둔다.
 *
 * [DecodedPage]는 Bitmap이 아니라 의존성 없는 뷰로 노출 — UI 모듈은 안드로이드
 * Bitmap을 자체적으로 들어 올린다. M0에서는 reference 구현이 없고 이 인터페이스의
 * 사용처는 [ReaderViewModel]의 프리로드 호출 한 곳뿐.
 */
interface PageDecoder {
    /**
     * [pageIndex]를 viewport에 맞춰 디코드한다. 호출자는 결과를 캐시에 보관할 책임.
     * 취소(coroutine cancellation) 가능. cancel 되면 즉시 반환 시도.
     */
    suspend fun decode(
        pageIndex: Int,
        viewportWidth: Int,
        viewportHeight: Int,
        trimEnabled: Boolean,
    ): DecodedPage

    /**
     * 현재 화면에 보이는 페이지([visibleIndices])의 LRU 위치를 갱신해 캐시 eviction에서 보호.
     *
     * spread 모드처럼 캐시 한계(~5장) 안에서 7페이지를 프리로드할 때, 단순 디코드 순서로는
     * 가장 먼저 디코드된 visible 페이지가 LRU 가장 오래된 항목이 되어 새 인접 페이지가 들어오면
     * evict된다. 풀리프레시 시퀀스 중에는 [io.github.sejoung.panelyink.reader.ui.ReaderView]의 `onDraw`가
     * 솔리드 색만 그리고 [io.github.sejoung.panelyink.reader.session.CbzBookSession.pageBitmap]을
     * 호출하지 않아 LRU touch가 자연 발생하지 않으므로 더 심각하다.
     *
     * 캐시에 없는 인덱스에 대한 호출은 no-op. 비non-suspending — 매 디코드 직전 호출 비용 무시할 만함.
     */
    fun keepWarm(visibleIndices: IntArray)
}

/** 디코더 출력. 실제 픽셀 데이터를 어떻게 들 것인지는 구현체가 결정. */
data class DecodedPage(
    val pageIndex: Int,
    val width: Int,
    val height: Int,
    /** 구현체별 비트맵 핸들 (예: android.graphics.Bitmap). UI 모듈에서만 캐스팅. */
    val payload: Any,
)
