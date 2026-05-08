package io.github.sejoung.panelyink.core.position

/**
 * 책별 마지막 페이지 + 전체 페이지 수를 영속 저장한다 — PRD §6.1 "Resume" 1차 키 +
 * M3 라이브러리 진행률 배지.
 *
 * - 키는 [PositionKey.bookId] (URI에서 파생된 SHA-1 hex) — 같은 폴더 안에서
 *   파일이 추가·삭제되어도 식별 유지
 * - 값은 [BookProgress] (pageIndex 0-base + pageCount 전체)
 * - M3에서 SharedPreferences → Room으로 이전(`RoomPositionRepository`).
 *   `suspend` 시그니처로 메인스레드 SQLite I/O 차단 방지.
 *
 * 구현은 [io.github.sejoung.panelyink.data.db.RoomPositionRepository] 참조.
 */
interface PositionRepository {
    /** 마지막으로 본 위치 + 책 길이. 기록 없으면 null. */
    suspend fun load(bookId: String): BookProgress?

    /**
     * @param pageCount 책의 전체 페이지 수. 0이면 unknown(라이브러리 진행률 미표시).
     * `ReaderViewModel.pageCount`를 그대로 넘긴다.
     */
    suspend fun save(bookId: String, pageIndex: Int, pageCount: Int)
}
