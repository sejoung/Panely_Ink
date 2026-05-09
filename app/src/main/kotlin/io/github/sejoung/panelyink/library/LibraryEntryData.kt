package io.github.sejoung.panelyink.library

import io.github.sejoung.panelyink.core.position.BookProgress
import io.github.sejoung.panelyink.core.position.PositionRepository
import io.github.sejoung.panelyink.core.position.PositionKey
import io.github.sejoung.panelyink.core.sort.NaturalOrderComparator
import io.github.sejoung.panelyink.data.db.CoverMetaRepository
import io.github.sejoung.panelyink.data.db.CoverStatus

internal data class PrefetchedBookData(
    val progress: Map<String, BookProgress>,
    val coverStatus: Map<String, CoverStatus>,
)

internal suspend fun prefetchBookData(
    entries: List<LibraryEntry>,
    positionRepo: PositionRepository,
    coverMetaRepo: CoverMetaRepository,
): PrefetchedBookData {
    val books = entries.filterIsInstance<BookEntry>()
    if (books.isEmpty()) return PrefetchedBookData(emptyMap(), emptyMap())
    val bookIds = books.map { PositionKey.bookIdFromUri(it.bookIdSource) }
    val progressMap = positionRepo.loadProgressMap(bookIds)
        .filterValues { it.isKnown }
    val statusMap = coverMetaRepo.loadMap(bookIds)
        .mapValues { it.value.status }
    return PrefetchedBookData(progress = progressMap, coverStatus = statusMap)
}

internal suspend fun sortLibraryEntries(
    entries: List<LibraryEntry>,
    mode: SortMode,
    positionRepo: PositionRepository,
): List<LibraryEntry> {
    val folders = entries.filterIsInstance<FolderEntry>()
    val books = entries.filterIsInstance<BookEntry>()
    val sortedBooks = when (mode) {
        SortMode.Name -> books
        SortMode.LastOpened -> {
            val ids = books.map { PositionKey.bookIdFromUri(it.bookIdSource) }
            val timestamps = positionRepo.loadUpdatedAtMap(ids)
            books.sortedWith(
                compareByDescending<BookEntry> {
                    timestamps[PositionKey.bookIdFromUri(it.bookIdSource)] ?: 0L
                }.thenBy(NaturalOrderComparator) { it.displayName },
            )
        }
    }
    return folders + sortedBooks
}
