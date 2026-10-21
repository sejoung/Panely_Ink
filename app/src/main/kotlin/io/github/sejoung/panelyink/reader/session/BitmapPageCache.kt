package io.github.sejoung.panelyink.reader.session

import android.graphics.Bitmap
import android.util.LruCache

/**
 * 페이지 비트맵 LRU. byte 단위로 사이즈를 산정한다.
 *
 * PRD §9: 페이지 캐시 ~100MB 상한. 100MB는 Int 안에 들어가므로
 * `LruCache<Int, Bitmap>` 의 Int 사이즈로 충분.
 */
class BitmapPageCache(maxBytes: Long) {

    private val maxBytesInt: Int = maxBytes.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

    /** 캐시 상한(byte). 디코드 단계에서 비트맵 1장의 크기 상한을 정할 때 참조. */
    val maxBytes: Long get() = maxBytesInt.toLong()

    private val cache = object : LruCache<Int, Bitmap>(maxBytesInt) {
        override fun sizeOf(key: Int, value: Bitmap): Int = value.byteCount
    }

    @Synchronized
    fun get(index: Int): Bitmap? = cache.get(index)

    @Synchronized
    fun put(index: Int, bitmap: Bitmap) {
        cache.put(index, bitmap)
    }

    /**
     * 최근에 덜 쓰인 것부터 [maxBytes]의 절반까지 evict — OOM 회복용. 화면에 보이는 페이지는
     * keepWarm/onDraw의 [get]으로 가장 최근에 접근되므로 마지막까지 남는다.
     */
    @Synchronized
    fun trimToHalf() {
        cache.trimToSize(maxBytesInt / 2)
    }

    @Synchronized
    fun clear() {
        cache.evictAll()
    }
}
