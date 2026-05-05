package io.github.sejoung.panelyink.core.archive

/** 아카이브 안 페이지 1장을 식별하는 메타데이터. 본문 디코드는 별도 모듈. */
data class CbzPage(
    val name: String,
    val size: Long,
)
