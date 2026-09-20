package io.github.sejoung.panelyink.core.archive

/** 아카이브 안 페이지 1장을 식별하는 메타데이터. 본문 디코드는 별도 모듈. */
data class CbzPage(
    val name: String,
    val size: Long,
    /**
     * central directory 안 entry 순번 — [CbzArchive.openPage]가 이름 대신 이걸로 entry를 찾는다.
     * 이름이 중복되거나(깨진 인코딩 포함) 해도 정확히 이 페이지의 entry가 열린다. -1이면 이름으로 조회.
     */
    val entryIndex: Int = -1,
)
