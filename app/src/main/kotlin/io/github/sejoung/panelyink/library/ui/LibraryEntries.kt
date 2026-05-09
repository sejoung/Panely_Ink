package io.github.sejoung.panelyink.library.ui

import io.github.sejoung.panelyink.library.LibraryViewModel
import io.github.sejoung.panelyink.library.model.BookEntry
import io.github.sejoung.panelyink.library.model.FolderEntry
import io.github.sejoung.panelyink.library.model.LibraryEntry
import io.github.sejoung.panelyink.library.model.SortMode
import io.github.sejoung.panelyink.library.model.ViewMode
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.sejoung.panelyink.R
import io.github.sejoung.panelyink.core.position.BookProgress
import io.github.sejoung.panelyink.core.position.PositionKey
import io.github.sejoung.panelyink.ui.components.PanelyBookIcon
import io.github.sejoung.panelyink.ui.components.PanelyChevronRightIcon
import io.github.sejoung.panelyink.ui.components.PanelyFolderIcon
import io.github.sejoung.panelyink.ui.theme.LocalPanelyInkSpacing
import io.github.sejoung.panelyink.ui.theme.LocalPanelyInkTypography
import io.github.sejoung.panelyink.ui.theme.PanelyInkColors
import kotlin.math.roundToInt

@Composable
internal fun LibraryList(
    entries: List<LibraryEntry>,
    viewMode: ViewMode,
    folderCounts: Map<android.net.Uri, Int>,
    folderCovers: Map<android.net.Uri, ImageBitmap>,
    covers: Map<String, ImageBitmap>,
    bookProgress: Map<String, BookProgress>,
    onEnterFolder: (FolderEntry) -> Unit,
    onOpenBook: (BookEntry, List<BookEntry>) -> Unit,
    onRequestCount: (FolderEntry) -> Unit,
    onRequestCover: (BookEntry) -> Unit,
    onRequestFolderCover: (FolderEntry) -> Unit,
    onRequestProgress: (BookEntry) -> Unit,
) {
    val siblings = remember(entries) { entries.filterIsInstance<BookEntry>() }
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(items = entries, key = { it.stableKey }) { entry ->
            when (entry) {
                is FolderEntry -> FolderRow(
                    entry = entry,
                    count = folderCounts[entry.documentUri],
                    cover = folderCovers[entry.documentUri],
                    showCover = viewMode == ViewMode.Cover,
                    onClick = { onEnterFolder(entry) },
                    onRequestCount = onRequestCount,
                    onRequestCover = onRequestFolderCover,
                )
                is BookEntry -> {
                    val bookId = remember(entry.bookIdSource) {
                        PositionKey.bookIdFromUri(entry.bookIdSource)
                    }
                    when (viewMode) {
                        ViewMode.Cover -> BookRow(
                            entry = entry,
                            cover = covers[bookId],
                            progress = bookProgress[bookId],
                            onClick = { onOpenBook(entry, siblings) },
                            onRequestCover = onRequestCover,
                            onRequestProgress = onRequestProgress,
                        )
                        ViewMode.List -> BookRowCompact(
                            entry = entry,
                            progress = bookProgress[bookId],
                            onClick = { onOpenBook(entry, siblings) },
                            onRequestProgress = onRequestProgress,
                        )
                        ViewMode.Grid -> Unit
                    }
                }
            }
            HairlineDivider()
        }
    }
}

@Composable
internal fun LibraryGrid(
    entries: List<LibraryEntry>,
    folderCounts: Map<android.net.Uri, Int>,
    folderCovers: Map<android.net.Uri, ImageBitmap>,
    covers: Map<String, ImageBitmap>,
    bookProgress: Map<String, BookProgress>,
    onEnterFolder: (FolderEntry) -> Unit,
    onOpenBook: (BookEntry, List<BookEntry>) -> Unit,
    onRequestCount: (FolderEntry) -> Unit,
    onRequestCover: (BookEntry) -> Unit,
    onRequestFolderCover: (FolderEntry) -> Unit,
    onRequestProgress: (BookEntry) -> Unit,
) {
    val spacing = LocalPanelyInkSpacing.current
    val siblings = remember(entries) { entries.filterIsInstance<BookEntry>() }
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        modifier = Modifier
            .fillMaxSize()
            .padding(spacing.space2),
        verticalArrangement = Arrangement.spacedBy(spacing.space2),
        horizontalArrangement = Arrangement.spacedBy(spacing.space2),
    ) {
        items(items = entries, key = { it.stableKey }) { entry ->
            when (entry) {
                is FolderEntry -> FolderGridCell(
                    entry = entry,
                    count = folderCounts[entry.documentUri],
                    cover = folderCovers[entry.documentUri],
                    onClick = { onEnterFolder(entry) },
                    onRequestCount = onRequestCount,
                    onRequestCover = onRequestFolderCover,
                )
                is BookEntry -> {
                    val bookId = remember(entry.bookIdSource) {
                        PositionKey.bookIdFromUri(entry.bookIdSource)
                    }
                    BookGridCell(
                        entry = entry,
                        cover = covers[bookId],
                        progress = bookProgress[bookId],
                        onClick = { onOpenBook(entry, siblings) },
                        onRequestCover = onRequestCover,
                        onRequestProgress = onRequestProgress,
                    )
                }
            }
        }
    }
}

@Composable
private fun BookRowCompact(
    entry: BookEntry,
    progress: BookProgress?,
    onClick: () -> Unit,
    onRequestProgress: (BookEntry) -> Unit,
) {
    val typography = LocalPanelyInkTypography.current
    val spacing = LocalPanelyInkSpacing.current

    LaunchedEffect(entry.documentUri) { onRequestProgress(entry) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = spacing.space4, vertical = spacing.space2),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.space2),
    ) {
        PanelyBookIcon()
        Text(
            text = entry.displayName,
            style = typography.list,
            color = PanelyInkColors.Ink,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        ProgressText(progress)
    }
}

@Composable
private fun BookGridCell(
    entry: BookEntry,
    cover: ImageBitmap?,
    progress: BookProgress?,
    onClick: () -> Unit,
    onRequestCover: (BookEntry) -> Unit,
    onRequestProgress: (BookEntry) -> Unit,
) {
    val typography = LocalPanelyInkTypography.current
    LaunchedEffect(entry.documentUri) {
        onRequestCover(entry)
        onRequestProgress(entry)
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        CoverTile(cover = cover, placeholder = { PanelyBookIcon() }) {
            ProgressBadge(progress)
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = entry.displayName,
            style = typography.body,
            color = PanelyInkColors.Ink,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun FolderGridCell(
    entry: FolderEntry,
    count: Int?,
    cover: ImageBitmap?,
    onClick: () -> Unit,
    onRequestCount: (FolderEntry) -> Unit,
    onRequestCover: (FolderEntry) -> Unit,
) {
    val typography = LocalPanelyInkTypography.current
    LaunchedEffect(entry.documentUri) {
        onRequestCount(entry)
        onRequestCover(entry)
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        CoverTile(cover = cover, placeholder = { PanelyFolderIcon(size = 64.dp) }) {
            CountBadge(count)
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = entry.displayName,
            style = typography.body,
            color = PanelyInkColors.Ink,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun FolderRow(
    entry: FolderEntry,
    count: Int?,
    cover: ImageBitmap?,
    showCover: Boolean,
    onClick: () -> Unit,
    onRequestCount: (FolderEntry) -> Unit,
    onRequestCover: (FolderEntry) -> Unit,
) {
    val typography = LocalPanelyInkTypography.current
    val spacing = LocalPanelyInkSpacing.current

    LaunchedEffect(entry.documentUri) {
        onRequestCount(entry)
        if (showCover) onRequestCover(entry)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = spacing.space4, vertical = spacing.space2),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.space2),
    ) {
        if (showCover) {
            CoverSlot(cover = cover, placeholder = { PanelyFolderIcon() })
        } else {
            PanelyFolderIcon()
        }
        Text(
            text = entry.displayName,
            style = typography.list,
            color = PanelyInkColors.Ink,
            modifier = Modifier.weight(1f),
            maxLines = if (showCover) 2 else 1,
            overflow = TextOverflow.Ellipsis,
        )
        CountText(count)
        PanelyChevronRightIcon()
    }
}

@Composable
private fun BookRow(
    entry: BookEntry,
    cover: ImageBitmap?,
    progress: BookProgress?,
    onClick: () -> Unit,
    onRequestCover: (BookEntry) -> Unit,
    onRequestProgress: (BookEntry) -> Unit,
) {
    val typography = LocalPanelyInkTypography.current
    val spacing = LocalPanelyInkSpacing.current

    LaunchedEffect(entry.documentUri) {
        onRequestCover(entry)
        onRequestProgress(entry)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = spacing.space4, vertical = spacing.space2),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.space2),
    ) {
        CoverSlot(cover = cover, placeholder = { PanelyBookIcon() }) {
            ProgressBadge(progress)
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.displayName,
                style = typography.list,
                color = PanelyInkColors.Ink,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = formatBytes(entry.sizeBytes),
                style = typography.caption,
                color = PanelyInkColors.Mute,
            )
        }
    }
}

@Composable
private fun CoverSlot(
    cover: ImageBitmap?,
    placeholder: @Composable () -> Unit,
    badge: @Composable () -> Unit = {},
) {
    Box(
        modifier = Modifier
            .size(width = 80.dp, height = 112.dp)
            .background(PanelyInkColors.Paper),
        contentAlignment = Alignment.Center,
    ) {
        CoverImageOrPlaceholder(cover = cover, placeholder = placeholder)
        Box(modifier = Modifier.align(Alignment.BottomEnd)) {
            badge()
        }
    }
}

@Composable
private fun CoverTile(
    cover: ImageBitmap?,
    placeholder: @Composable () -> Unit,
    badge: @Composable () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(0.71f)
            .background(PanelyInkColors.Paper)
            .border(1.dp, PanelyInkColors.Hairline),
        contentAlignment = Alignment.Center,
    ) {
        CoverImageOrPlaceholder(cover = cover, placeholder = placeholder)
        Box(modifier = Modifier.align(Alignment.BottomEnd)) {
            badge()
        }
    }
}

@Composable
private fun CoverImageOrPlaceholder(
    cover: ImageBitmap?,
    placeholder: @Composable () -> Unit,
) {
    if (cover != null) {
        Image(
            bitmap = cover,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxWidth(),
        )
    } else {
        placeholder()
    }
}

@Composable
private fun ProgressText(progress: BookProgress?) {
    if (progress != null && progress.isKnown) {
        val typography = LocalPanelyInkTypography.current
        Text(
            text = "${(progress.percent * 100).roundToInt()}%",
            style = typography.caption,
            color = PanelyInkColors.Mute,
        )
    }
}

@Composable
private fun CountText(count: Int?) {
    if (count != null) {
        val typography = LocalPanelyInkTypography.current
        Text(
            text = if (count == 0) {
                stringResource(R.string.common_empty)
            } else {
                stringResource(R.string.library_book_count, count)
            },
            style = typography.caption,
            color = PanelyInkColors.Mute,
        )
    }
}

@Composable
private fun ProgressBadge(progress: BookProgress?) {
    if (progress != null && progress.isKnown) {
        Badge(text = "${(progress.percent * 100).roundToInt()}%")
    }
}

@Composable
private fun CountBadge(count: Int?) {
    if (count != null) {
        Badge(
            text = if (count == 0) {
                stringResource(R.string.common_empty)
            } else {
                stringResource(R.string.library_book_count, count)
            },
        )
    }
}

@Composable
private fun Badge(text: String) {
    val typography = LocalPanelyInkTypography.current
    Box(
        modifier = Modifier
            .background(PanelyInkColors.Paper)
            .border(1.dp, PanelyInkColors.Ink)
            .padding(horizontal = 4.dp, vertical = 1.dp),
    ) {
        Text(
            text = text,
            style = typography.caption,
            color = PanelyInkColors.Ink,
        )
    }
}
