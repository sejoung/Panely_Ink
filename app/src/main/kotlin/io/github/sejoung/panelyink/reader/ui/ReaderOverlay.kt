package io.github.sejoung.panelyink.reader.ui

import io.github.sejoung.panelyink.reader.ReaderState
import io.github.sejoung.panelyink.reader.ReaderViewModel
import io.github.sejoung.panelyink.reader.input.ReaderInput
import io.github.sejoung.panelyink.reader.model.BookSettings
import io.github.sejoung.panelyink.reader.model.ReadingDirection
import io.github.sejoung.panelyink.reader.model.SeriesContext
import io.github.sejoung.panelyink.reader.session.CbzBookSession
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.sejoung.panelyink.R
import io.github.sejoung.panelyink.ui.components.PanelyArrowBackIcon
import io.github.sejoung.panelyink.ui.components.PanelyArrowForwardIcon
import io.github.sejoung.panelyink.ui.theme.LocalPanelyInkSpacing
import io.github.sejoung.panelyink.ui.theme.LocalPanelyInkTypography
import io.github.sejoung.panelyink.ui.theme.PanelyInkColors

@Composable
internal fun ReaderOverlayLayer(
    state: ReaderState,
    viewModel: ReaderViewModel,
    context: SeriesContext,
    bookTitle: String,
    menuOpen: Boolean,
    settingsOpen: Boolean,
    bookmarksOpen: Boolean,
    currentPageBookmarked: Boolean,
    bookmarkedPages: Set<Int>,
    onOpenMenu: () -> Unit,
    onCloseMenu: () -> Unit,
    onOpenSettings: () -> Unit,
    onCloseSettings: () -> Unit,
    onOpenBookmarks: () -> Unit,
    onCloseBookmarks: () -> Unit,
    onExit: () -> Unit,
    onJumpToPage: (Int) -> Unit,
    onToggleBookmark: () -> Unit,
    onDeleteBookmark: (Int) -> Unit,
    onNavigate: (io.github.sejoung.panelyink.library.model.BookEntry, BookSettings) -> Unit,
) {
    if (!menuOpen && !bookmarksOpen) {
        TapRegions(
            directionIsRtl = state.direction == ReadingDirection.Rtl,
            onPrev = viewModel::goPrevious,
            onNext = viewModel::goNext,
            onMenu = onOpenMenu,
        )
        AdjacentBookPrompt(
            state = state,
            pageCount = viewModel.pageCount,
            context = context,
            settingsOpen = settingsOpen || bookmarksOpen,
            onNavigate = onNavigate,
            modifier = Modifier.fillMaxSize(),
        )
    } else {
        ReaderMenu(
            state = state,
            bookTitle = bookTitle,
            pageCount = viewModel.pageCount,
            previousBookTitle = context.previousBook?.displayName,
            nextBookTitle = context.nextBook?.displayName,
            currentPageBookmarked = currentPageBookmarked,
            onJumpToPage = onJumpToPage,
            onPreviousBook = {
                context.previousBook?.let { onNavigate(it, state.toBookSettings()) }
            },
            onNextBook = {
                context.nextBook?.let { onNavigate(it, state.toBookSettings()) }
            },
            onToggleBookmark = onToggleBookmark,
            onOpenBookmarks = onOpenBookmarks,
            onOpenSettings = onOpenSettings,
            onExitToLibrary = {
                onCloseMenu()
                onExit()
            },
            onClose = onCloseMenu,
        )
    }

    if (settingsOpen) {
        ReaderSettingsScreen(
            state = state,
            onFitModeChange = viewModel::setFitMode,
            onDirectionChange = viewModel::setDirection,
            onTrimEnabledChange = viewModel::setTrimEnabled,
            onContrastChange = viewModel::setContrast,
            onInvertEnabledChange = viewModel::setInvertEnabled,
            onTriggerFullRefresh = viewModel::triggerFullRefresh,
            onBack = {
                onCloseSettings()
                onCloseMenu()
            },
        )
    }

    if (bookmarksOpen) {
        BookmarkListScreen(
            bookmarks = bookmarkedPages,
            currentPage = state.currentPage,
            onSelect = { page ->
                onJumpToPage(page)
                onCloseBookmarks()
                onCloseMenu()
            },
            onDelete = onDeleteBookmark,
            onBack = {
                onCloseBookmarks()
                onCloseMenu()
            },
        )
    }
}

@Composable
private fun AdjacentBookPrompt(
    state: ReaderState,
    pageCount: Int,
    context: SeriesContext,
    settingsOpen: Boolean,
    onNavigate: (io.github.sejoung.panelyink.library.model.BookEntry, BookSettings) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (settingsOpen) return
    val previousBook = previousBookForBoundary(state, context)
    val nextBook = nextBookForBoundary(state, pageCount, context)

    Box(modifier = modifier) {
        when {
            nextBook != null -> AdjacentBookCard(
                label = stringResource(R.string.reader_next_book),
                title = nextBook.displayName,
                forward = true,
                onClick = { onNavigate(nextBook, state.toBookSettings()) },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
            previousBook != null -> AdjacentBookCard(
                label = stringResource(R.string.reader_previous_book),
                title = previousBook.displayName,
                forward = false,
                onClick = { onNavigate(previousBook, state.toBookSettings()) },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }
}

@Composable
private fun AdjacentBookCard(
    label: String,
    title: String,
    forward: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val typography = LocalPanelyInkTypography.current
    val spacing = LocalPanelyInkSpacing.current

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(PanelyInkColors.Paper)
            .border(2.dp, PanelyInkColors.Ink)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = spacing.space3, vertical = spacing.space2),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.space2),
    ) {
        if (!forward) PanelyArrowBackIcon(tint = PanelyInkColors.Ink)
        Text(
            text = label,
            style = typography.body,
            color = PanelyInkColors.Ink,
        )
        Text(
            text = title,
            style = typography.caption,
            color = PanelyInkColors.Mute,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (forward) PanelyArrowForwardIcon(tint = PanelyInkColors.Ink)
    }
}

@Composable
private fun TapRegions(
    directionIsRtl: Boolean,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onMenu: () -> Unit,
) {
    val left: () -> Unit = if (directionIsRtl) onNext else onPrev
    val right: () -> Unit = if (directionIsRtl) onPrev else onNext

    Row(
        modifier = Modifier.fillMaxSize(),
        horizontalArrangement = Arrangement.Start,
    ) {
        TapRegion(weight = 0.3f, onClick = left)
        TapRegion(weight = 0.4f, onClick = onMenu)
        TapRegion(weight = 0.3f, onClick = right)
    }
}

@Composable
private fun RowScope.TapRegion(weight: Float, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .weight(weight)
            .fillMaxHeight()
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
            ),
    )
}
