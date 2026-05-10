package io.github.sejoung.panelyink.data.db

/**
 * SQLite/Android Room의 host parameter 한도(999) 우회용 chunk 크기.
 *
 * `WHERE id IN (:list)` 쿼리에 1000+ 책을 넣으면 SQLite가 SQLITE_LIMIT_VARIABLE_NUMBER로
 * 거부한다. Repository 레이어에서 이 상수로 청크 분할 후 N번 쿼리를 합치게 한다.
 *
 * 실제 한도는 999이지만 안전 마진으로 900. 한도가 SoC/Android 버전에 따라 달라질 가능성도 차단.
 */
internal const val SQL_IN_CLAUSE_CHUNK_SIZE = 900

/**
 * [this]를 [chunkSize] 단위로 잘라 [block]을 호출하고 결과를 평탄화한다.
 *
 * SELECT 계열 IN-clause 쿼리에 사용 — 각 청크의 결과 List를 합쳐 반환.
 */
internal suspend fun <T, R> List<T>.flatMapInChunks(
    chunkSize: Int = SQL_IN_CLAUSE_CHUNK_SIZE,
    block: suspend (List<T>) -> List<R>,
): List<R> {
    if (isEmpty()) return emptyList()
    if (size <= chunkSize) return block(this)
    val out = ArrayList<R>(size)
    chunked(chunkSize).forEach { chunk -> out.addAll(block(chunk)) }
    return out
}

/**
 * [this]를 [chunkSize] 단위로 잘라 [block]을 호출. 부수효과(예: DELETE) 전용.
 */
internal suspend fun <T> List<T>.forEachChunk(
    chunkSize: Int = SQL_IN_CLAUSE_CHUNK_SIZE,
    block: suspend (List<T>) -> Unit,
) {
    if (isEmpty()) return
    if (size <= chunkSize) {
        block(this)
        return
    }
    chunked(chunkSize).forEach { chunk -> block(chunk) }
}
