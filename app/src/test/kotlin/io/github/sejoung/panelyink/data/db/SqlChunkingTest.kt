package io.github.sejoung.panelyink.data.db

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `SqlChunking` 헬퍼가 SQLite host param 한도(999) 우회를 위해 청크 분할을 정확히 수행하는지 검증.
 * 핵심: 빈 입력은 즉시 0건, 한도 이하는 단일 호출, 한도 초과는 N개 청크로 정확히 분할되고 결과가 합쳐짐.
 */
class SqlChunkingTest {

    @Test
    fun flatMapInChunks_emptyListReturnsEmpty() = runBlocking {
        var calls = 0
        val result = emptyList<Int>().flatMapInChunks<Int, Int>(chunkSize = 10) { chunk ->
            calls++
            chunk.map { it * 2 }
        }
        assertEquals(emptyList<Int>(), result)
        assertEquals("empty input must not invoke block", 0, calls)
    }

    @Test
    fun flatMapInChunks_underChunkSizeMakesSingleCall() = runBlocking {
        val input = (1..50).toList()
        var calls = 0
        val result = input.flatMapInChunks(chunkSize = 100) { chunk ->
            calls++
            chunk.map { it * 10 }
        }
        assertEquals(input.map { it * 10 }, result)
        assertEquals(1, calls)
    }

    @Test
    fun flatMapInChunks_atChunkSizeBoundaryStillSingleCall() = runBlocking {
        val input = (1..100).toList()
        var calls = 0
        input.flatMapInChunks<Int, Int>(chunkSize = 100) { chunk ->
            calls++
            chunk
        }
        assertEquals(1, calls)
    }

    @Test
    fun flatMapInChunks_overChunkSizeSplitsAndConcatenatesInOrder() = runBlocking {
        val input = (1..2500).toList()
        val seenChunkSizes = mutableListOf<Int>()
        val result = input.flatMapInChunks(chunkSize = 900) { chunk ->
            seenChunkSizes += chunk.size
            chunk
        }
        // 900 + 900 + 700 = 2500
        assertEquals(listOf(900, 900, 700), seenChunkSizes)
        assertEquals(input, result)
    }

    @Test
    fun forEachChunk_emptyListSkipsBlock() = runBlocking {
        var calls = 0
        emptyList<String>().forEachChunk { calls++ }
        assertEquals(0, calls)
    }

    @Test
    fun forEachChunk_underChunkSizeSingleCall() = runBlocking {
        val input = listOf("a", "b", "c")
        val seen = mutableListOf<List<String>>()
        input.forEachChunk(chunkSize = 10) { seen += it }
        assertEquals(listOf(input), seen)
    }

    @Test
    fun forEachChunk_overChunkSizeSplitsRespectingBoundaries() = runBlocking {
        val input = (0 until 1500).toList()
        val seenChunkSizes = mutableListOf<Int>()
        var seenItemCount = 0
        input.forEachChunk(chunkSize = 900) { chunk ->
            seenChunkSizes += chunk.size
            seenItemCount += chunk.size
        }
        assertEquals(listOf(900, 600), seenChunkSizes)
        assertEquals(input.size, seenItemCount)
    }

    @Test
    fun defaultChunkSizeStaysUnderSqliteVariableLimit() {
        // SQLITE_LIMIT_VARIABLE_NUMBER 기본 한도 999. 안전 마진 포함.
        assertTrue(
            "SQL_IN_CLAUSE_CHUNK_SIZE must be < SQLite default 999 (saw $SQL_IN_CLAUSE_CHUNK_SIZE)",
            SQL_IN_CLAUSE_CHUNK_SIZE < 999,
        )
    }

    private fun assertTrue(message: String, condition: Boolean) {
        org.junit.Assert.assertTrue(message, condition)
    }
}
