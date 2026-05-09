package io.github.sejoung.panelyink.reader.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.sejoung.panelyink.R
import io.github.sejoung.panelyink.ui.components.PanelyArrowBackIcon
import io.github.sejoung.panelyink.ui.components.PanelyIconButton
import io.github.sejoung.panelyink.ui.components.PanelyTrashIcon
import io.github.sejoung.panelyink.ui.theme.LocalPanelyInkSpacing
import io.github.sejoung.panelyink.ui.theme.LocalPanelyInkTypography
import io.github.sejoung.panelyink.ui.theme.PanelyInkColors

@Composable
internal fun BookmarkListScreen(
    bookmarks: Set<Int>,
    currentPage: Int,
    onSelect: (Int) -> Unit,
    onDelete: (Int) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler { onBack() }

    val typography = LocalPanelyInkTypography.current
    val spacing = LocalPanelyInkSpacing.current
    val sorted = remember(bookmarks) { bookmarks.sorted() }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(PanelyInkColors.Paper),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = spacing.space3, vertical = spacing.space2),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.space2),
        ) {
            PanelyIconButton(onClick = onBack, primary = false) { tint ->
                PanelyArrowBackIcon(tint = tint)
            }
            Text(
                text = stringResource(R.string.reader_bookmarks_title),
                style = typography.title,
                color = PanelyInkColors.Ink,
            )
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(PanelyInkColors.Hairline),
        )

        if (sorted.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(spacing.space5),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(R.string.reader_bookmarks_empty),
                    style = typography.body,
                    color = PanelyInkColors.Mute,
                    textAlign = TextAlign.Center,
                )
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(items = sorted, key = { it }) { page ->
                    BookmarkRow(
                        page = page,
                        selected = page == currentPage,
                        onClick = { onSelect(page) },
                        onDelete = { onDelete(page) },
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(PanelyInkColors.Hairline),
                    )
                }
            }
        }
    }
}

@Composable
private fun BookmarkRow(
    page: Int,
    selected: Boolean,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    val typography = LocalPanelyInkTypography.current
    val spacing = LocalPanelyInkSpacing.current
    val bg = if (selected) PanelyInkColors.Ink else PanelyInkColors.Paper
    val fg = if (selected) PanelyInkColors.Paper else PanelyInkColors.Ink

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(bg)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = spacing.space4, vertical = spacing.space2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.reader_bookmark_page, page + 1),
            style = typography.list,
            color = fg,
            modifier = Modifier.weight(1f),
        )
        PanelyIconButton(onClick = onDelete, primary = selected) { tint ->
            PanelyTrashIcon(tint = tint)
        }
    }
}
