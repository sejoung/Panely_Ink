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
    ): DecodedPage
}

/** 디코더 출력. 실제 픽셀 데이터를 어떻게 들 것인지는 구현체가 결정. */
data class DecodedPage(
    val pageIndex: Int,
    val width: Int,
    val height: Int,
    /** 구현체별 비트맵 핸들 (예: android.graphics.Bitmap). UI 모듈에서만 캐스팅. */
    val payload: Any,
)
