package io.github.sejoung.panelyink.library.ui

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
        inFolder -> stringResourceCompat(R.string.library_empty_folder_title)
        hasRoots -> stringResourceCompat(R.string.library_empty_root_title)
        else -> stringResourceCompat(R.string.library_empty_title)
      }
      val body = when {
        scanning -> ""
        inFolder -> stringResourceCompat(R.string.library_empty_folder_body)
        hasRoots -> stringResourceCompat(R.string.library_empty_root_body)
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
          label = stringResourceCompat(R.string.library_add_folder),
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
        text = stringResourceCompat(R.string.library_search_empty_title),
        style = typography.display,
        color = PanelyInkColors.Ink,
        textAlign = TextAlign.Center,
      )
      if (query.isNotBlank()) {
        Spacer(Modifier.height(spacing.space2))
        Text(
          text = stringResourceCompat(R.string.library_search_empty_body, query),
          style = typography.body,
          color = PanelyInkColors.Mute,
          textAlign = TextAlign.Center,
        )
      }
    }
  }
}
