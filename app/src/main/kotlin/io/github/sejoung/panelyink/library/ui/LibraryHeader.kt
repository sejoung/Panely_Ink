package io.github.sejoung.panelyink.library.ui

import io.github.sejoung.panelyink.library.LibraryViewModel
import io.github.sejoung.panelyink.library.model.BookEntry
import io.github.sejoung.panelyink.library.model.FolderEntry
import io.github.sejoung.panelyink.library.model.LibraryEntry
import io.github.sejoung.panelyink.library.model.SortMode
import io.github.sejoung.panelyink.library.model.ViewMode
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.sejoung.panelyink.R
import io.github.sejoung.panelyink.ui.components.PanelyArrowBackIcon
import io.github.sejoung.panelyink.ui.components.PanelyCloseIcon
import io.github.sejoung.panelyink.ui.components.PanelyIconButton
import io.github.sejoung.panelyink.ui.components.PanelyRefreshIcon
import io.github.sejoung.panelyink.ui.components.PanelySearchIcon
import io.github.sejoung.panelyink.ui.components.PanelySettingsIcon
import io.github.sejoung.panelyink.ui.components.PanelySortIcon
import io.github.sejoung.panelyink.ui.theme.LocalPanelyInkSpacing
import io.github.sejoung.panelyink.ui.theme.LocalPanelyInkTypography
import io.github.sejoung.panelyink.ui.theme.PanelyInkColors

@Composable
internal fun LibraryHeader(
    path: List<FolderEntry>,
    scanning: Boolean,
    searchActive: Boolean,
    searchQuery: String,
    onActivateSearch: () -> Unit,
    onCancelSearch: () -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onSort: () -> Unit,
    onRefresh: () -> Unit,
    onUp: () -> Boolean,
) {
    val typography = LocalPanelyInkTypography.current
    val spacing = LocalPanelyInkSpacing.current
    if (searchActive) {
        SearchHeader(
            query = searchQuery,
            onQueryChange = onSearchQueryChange,
            onCancel = onCancelSearch,
        )
        return
    }
    val inFolder = path.isNotEmpty()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = spacing.space4, vertical = spacing.space2),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.space2),
        ) {
            if (inFolder) {
                PanelyIconButton(onClick = { onUp() }, primary = false) { tint ->
                    PanelyArrowBackIcon(tint = tint)
                }
            }
            Column(verticalArrangement = Arrangement.Center, modifier = Modifier.weight(1f)) {
                Text(
                    text = path.lastOrNull()?.displayName
                        ?: stringResourceCompat(R.string.library_title),
                    style = typography.title,
                    color = PanelyInkColors.Ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (path.isNotEmpty()) {
                    BreadcrumbLine(path = path)
                } else if (scanning) {
                    Text(
                        text = stringResourceCompat(R.string.library_scanning),
                        style = typography.caption,
                        color = PanelyInkColors.Mute,
                    )
                }
            }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.space1),
        ) {
            PanelyIconButton(onClick = onActivateSearch, primary = false) { tint ->
                PanelySearchIcon(tint = tint)
            }
            PanelyIconButton(onClick = onSort, primary = false) { tint ->
                PanelySortIcon(tint = tint)
            }
            PanelyIconButton(onClick = onRefresh, primary = false) { tint ->
                PanelyRefreshIcon(tint = tint)
            }
            PanelyIconButton(onClick = onOpenSettings, primary = false) { tint ->
                PanelySettingsIcon(tint = tint)
            }
        }
    }
}

@Composable
private fun SearchHeader(
    query: String,
    onQueryChange: (String) -> Unit,
    onCancel: () -> Unit,
) {
    val typography = LocalPanelyInkTypography.current
    val spacing = LocalPanelyInkSpacing.current
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = spacing.space4, vertical = spacing.space2),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.space2),
    ) {
        PanelyIconButton(onClick = onCancel, primary = false) { tint ->
            PanelyCloseIcon(tint = tint)
        }
        Box(
            modifier = Modifier
                .weight(1f)
                .height(48.dp)
                .background(PanelyInkColors.Paper)
                .border(1.dp, PanelyInkColors.Hairline)
                .padding(horizontal = spacing.space2),
            contentAlignment = Alignment.CenterStart,
        ) {
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                cursorBrush = SolidColor(PanelyInkColors.Ink),
                textStyle = typography.body.copy(color = PanelyInkColors.Ink),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { }),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester),
            )
            if (query.isEmpty()) {
                Text(
                    text = stringResourceCompat(R.string.library_search_placeholder),
                    style = typography.body,
                    color = PanelyInkColors.Mute,
                )
            }
        }
        if (query.isNotEmpty()) {
            PanelyIconButton(onClick = { onQueryChange("") }, primary = false) { tint ->
                PanelyCloseIcon(tint = tint)
            }
        }
    }
}

@Composable
private fun BreadcrumbLine(path: List<FolderEntry>) {
    val typography = LocalPanelyInkTypography.current
    val crumbs = listOf(stringResourceCompat(R.string.library_title)) +
        path.map { it.displayName }
    Text(
        text = crumbs.joinToString(separator = " ▸ "),
        style = typography.caption,
        color = PanelyInkColors.Mute,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}
