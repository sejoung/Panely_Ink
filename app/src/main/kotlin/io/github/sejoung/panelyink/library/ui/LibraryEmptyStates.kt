package io.github.sejoung.panelyink.library.ui

import io.github.sejoung.panelyink.library.LibraryViewModel
import io.github.sejoung.panelyink.library.model.BookEntry
import io.github.sejoung.panelyink.library.model.FolderEntry
import io.github.sejoung.panelyink.library.model.LibraryEntry
import io.github.sejoung.panelyink.library.model.SortMode
import io.github.sejoung.panelyink.library.model.ViewMode
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import io.github.sejoung.panelyink.R
import io.github.sejoung.panelyink.ui.components.PanelyTextButton
import io.github.sejoung.panelyink.ui.theme.LocalPanelyInkSpacing
import io.github.sejoung.panelyink.ui.theme.LocalPanelyInkTypography
import io.github.sejoung.panelyink.ui.theme.PanelyInkColors

@Composable
internal fun LibraryEmptyState(
    inFolder: Boolean,
    hasRoots: Boolean,
    scanning: Boolean,
    onAddFolder: () -> Unit,
) {
    val typography = LocalPanelyInkTypography.current
    val spacing = LocalPanelyInkSpacing.current
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = spacing.space5),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            val title = when {
                scanning -> stringResourceCompat(R.string.library_scanning)
                inFolder -> "이 폴더에는 항목이 없습니다"
                hasRoots -> "루트 폴더에 .cbz / .zip 파일이 없습니다"
                else -> stringResourceCompat(R.string.library_empty_title)
            }
            val body = when {
                scanning -> ""
                inFolder -> "한 단계 위로 돌아가거나 다른 폴더를 추가하세요."
                hasRoots -> "다른 폴더를 추가하거나 파일 확장자를 확인하세요."
                else -> stringResourceCompat(R.string.library_empty_body)
            }
            Text(
                text = title,
                style = typography.display,
                color = PanelyInkColors.Ink,
                textAlign = TextAlign.Center,
            )
            if (body.isNotEmpty()) {
                Spacer(Modifier.height(spacing.space2))
                Text(
                    text = body,
                    style = typography.body,
                    color = PanelyInkColors.Mute,
                    textAlign = TextAlign.Center,
                )
            }
            if (!scanning && !inFolder) {
                Spacer(Modifier.height(spacing.space3))
                PanelyTextButton(
                    label = "폴더 추가",
                    onClick = onAddFolder,
                    primary = !hasRoots,
                )
                Spacer(Modifier.height(spacing.space2))
                Text(
                    text = stringResourceCompat(R.string.library_picker_cancel_hint),
                    style = typography.caption,
                    color = PanelyInkColors.Mute,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
internal fun SearchEmptyState(query: String) {
    val typography = LocalPanelyInkTypography.current
    val spacing = LocalPanelyInkSpacing.current
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = spacing.space5),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "검색 결과가 없습니다",
                style = typography.display,
                color = PanelyInkColors.Ink,
                textAlign = TextAlign.Center,
            )
            if (query.isNotBlank()) {
                Spacer(Modifier.height(spacing.space2))
                Text(
                    text = "'$query' 와 일치하는 항목이 이 폴더에 없음",
                    style = typography.body,
                    color = PanelyInkColors.Mute,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}
