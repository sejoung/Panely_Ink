package io.github.sejoung.panelyink.core.position

import java.security.MessageDigest

/**
 * (책, 페이지) 쌍에 대한 영속 식별자. PRD §6.1 "Resume" / "페이지 북마크"의 1차 키.
 *
 * - bookId: 아카이브 위치(예: SAF document URI 문자열)에서 파생된 안정 해시
 * - pageIndex: 0-base, 자연 정렬된 페이지 목록 기준
 *
 * macOS Panely의 PositionKey 포팅. 라이브러리/시리즈가 같은 폴더 안에서
 * 파일이 추가·삭제되어도 책 식별이 유지되도록, 폴더 경로가 아닌 책 단위
 * 식별자를 쓴다.
 *
 * 진척률은 별도 (책 단위로 1개) — 여기서는 페이지 단위 위치 키만 다룬다.
 */
data class PositionKey(
    val bookId: String,
    val pageIndex: Int,
) {
    init {
        require(pageIndex >= 0) { "pageIndex must be >= 0" }
        require(bookId.isNotEmpty()) { "bookId must not be empty" }
    }

    /** Room/Preferences 등 단일 String 컬럼에 저장하기 위한 직렬 형식. */
    fun toStorageKey(): String = "$bookId#$pageIndex"

    companion object {
        fun fromStorageKey(s: String): PositionKey {
            val sep = s.lastIndexOf('#')
            require(sep > 0 && sep < s.length - 1) { "invalid storage key: $s" }
            val id = s.substring(0, sep)
            val idx = s.substring(sep + 1).toInt()
            return PositionKey(id, idx)
        }

        /**
         * URI/경로 문자열에서 안정적인 bookId를 만든다. SHA-1 hex 40자.
         * 동일 URI는 항상 동일 id로 매핑된다.
         */
        fun bookIdFromUri(uri: String): String {
            val digest = MessageDigest.getInstance("SHA-1").digest(uri.toByteArray(Charsets.UTF_8))
            return digest.joinToString(separator = "") { "%02x".format(it) }
        }
    }
}
