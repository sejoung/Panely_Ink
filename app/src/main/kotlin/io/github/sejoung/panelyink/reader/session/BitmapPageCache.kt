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

    private val cache = object : LruCache<Int, Bitmap>(maxBytesInt) {
        override fun sizeOf(key: Int, value: Bitmap): Int = value.byteCount
    }

    fun get(index: Int): Bitmap? = cache.get(index)

    fun put(index: Int, bitmap: Bitmap) {
        cache.put(index, bitmap)
    }

    fun clear() {
        cache.evictAll()
    }
}
