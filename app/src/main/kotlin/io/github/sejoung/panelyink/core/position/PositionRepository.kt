package io.github.sejoung.panelyink.core.position

import android.content.Context

/**
 * 책별 마지막 페이지를 영속 저장한다 — PRD §6.1 "Resume" 1차 키.
 *
 * - 키는 [PositionKey.bookId] (URI에서 파생된 SHA-1 hex) — 같은 폴더 안에서
 *   파일이 추가·삭제되어도 식별 유지
 * - 값은 페이지 인덱스 (0-base)
 * - 저장은 페이지 변경마다 호출되도록 의도되었으므로 구현은 가벼워야 한다
 *
 * v1.0은 SharedPreferences 구현. 책 수가 많아지면(1000권+) Room으로 교체 (M3).
 */
interface PositionRepository {
    /** 마지막으로 본 페이지 인덱스. 기록 없으면 null. */
    fun load(bookId: String): Int?

    /** [PositionKey.pageIndex]를 [PositionKey.bookId] 키로 저장. */
    fun save(key: PositionKey)
}

/**
 * SharedPreferences 기반 구현. `apply()`라 메인스레드 블록이 없고 다중 호출이
 * 합쳐진다 — 매 페이지 이동마다 호출해도 안전.
 */
class SharedPreferencesPositionRepository(
    context: Context,
) : PositionRepository {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun load(bookId: String): Int? =
        if (prefs.contains(bookId)) prefs.getInt(bookId, 0) else null

    override fun save(key: PositionKey) {
        prefs.edit().putInt(key.bookId, key.pageIndex).apply()
    }

    companion object {
        private const val PREFS_NAME = "panely_ink_positions"
    }
}
