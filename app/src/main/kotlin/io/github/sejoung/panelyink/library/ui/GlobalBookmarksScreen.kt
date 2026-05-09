package io.github.sejoung.panelyink.library.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import io.github.sejoung.panelyink.R
import io.github.sejoung.panelyink.library.GlobalBookmarkItem
import io.github.sejoung.panelyink.ui.components.PanelyArrowBackIcon
import io.github.sejoung.panelyink.ui.components.PanelyBookmarkIcon
import io.github.sejoung.panelyink.ui.components.PanelyChevronRightIcon
import io.github.sejoung.panelyink.ui.components.PanelyIconButton
import io.github.sejoung.panelyink.ui.components.PanelyRefreshIcon
import io.github.sejoung.panelyink.ui.components.PanelyTrashIcon
import io.github.sejoung.panelyink.ui.theme.LocalPanelyInkSpacing
import io.github.sejoung.panelyink.ui.theme.LocalPanelyInkTypography
import io.github.sejoung.panelyink.ui.theme.PanelyInkColors

@Composable
internal fun GlobalBookmarksScreen(
    items: List<GlobalBookmarkItem>,
    loading: Boolean,
    onRefresh: () -> Unit,
    onOpen: (GlobalBookmarkItem) -> Unit,
    onDelete: (GlobalBookmarkItem) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler { onBack() }

    val spacing = LocalPanelyInkSpacing.current
    val typography = LocalPanelyInkTypography.current

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(PanelyInkColors.Paper),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = spacing.space4, vertical = spacing.space2),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.space2),
        ) {
            PanelyIconButton(onClick = onBack, primary = false) { tint ->
                PanelyArrowBackIcon(tint = tint)
            }
            Text(
                text = stringResource(R.string.library_bookmarks_title),
                style = typography.title,
                color = PanelyInkColors.Ink,
                modifier = Modifier.weight(1f),
            )
            PanelyIconButton(onClick = onRefresh, primary = false) { tint ->
                PanelyRefreshIcon(tint = tint)
            }
        }
        HairlineDivider()

        when {
            loading -> CenterText(text = stringResource(R.string.library_bookmarks_loading))
            items.isEmpty() -> CenterText(text = stringResource(R.string.library_bookmarks_empty))
            else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(items = items, key = { "${it.bookId}#${it.pageIndex}" }) { item ->
                    GlobalBookmarkRow(
                        item = item,
                        onClick = { onOpen(item) },
                        onDelete = { onDelete(item) },
                    )
                    HairlineDivider()
                }
            }
        }
    }
}

@Composable
private fun GlobalBookmarkRow(
    item: GlobalBookmarkItem,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    val spacing = LocalPanelyInkSpacing.current
    val typography = LocalPanelyInkTypography.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = spacing.space4, vertical = spacing.space2),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.space2),
    ) {
        PanelyBookmarkIcon()
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.book.displayName,
                style = typography.list,
                color = PanelyInkColors.Ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = stringResource(R.string.reader_bookmark_page, item.pageIndex + 1),
                style = typography.caption,
                color = PanelyInkColors.Mute,
                maxLines = 1,
            )
        }
        PanelyChevronRightIcon()
        PanelyIconButton(onClick = onDelete, primary = false) { tint ->
            PanelyTrashIcon(tint = tint)
        }
    }
}

@Composable
private fun CenterText(text: String) {
    val spacing = LocalPanelyInkSpacing.current
    val typography = LocalPanelyInkTypography.current
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(spacing.space5),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = typography.body,
            color = PanelyInkColors.Mute,
            textAlign = TextAlign.Center,
        )
    }
}
