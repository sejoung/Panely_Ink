package io.github.sejoung.panelyink.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.sejoung.panelyink.R
import io.github.sejoung.panelyink.core.preferences.ReadingDirection
import io.github.sejoung.panelyink.ui.theme.LocalPanelyInkTypography
import io.github.sejoung.panelyink.ui.theme.PanelyInkColors

@Composable
internal fun GroupHeader(text: String) {
  val typography = LocalPanelyInkTypography.current
  Text(
    text = text,
    style = typography.list,
    color = PanelyInkColors.Ink,
  )
}

@Composable
internal fun DirectionSegments(
  selected: ReadingDirection,
  onSelect: (ReadingDirection) -> Unit,
) {
  val options = listOf(
    ReadingDirection.Ltr to stringResource(R.string.reader_direction_ltr),
    ReadingDirection.Rtl to stringResource(R.string.reader_direction_rtl),
  )
  Segments(
    options = options,
    isSelected = { it == selected },
    labelOf = { options.first { p -> p.first == it }.second },
    onSelect = onSelect,
  )
}

@Composable
internal fun InvertSegments(
  enabled: Boolean,
  onSelect: (Boolean) -> Unit,
) {
  val options = listOf(
    false to stringResource(R.string.common_off),
    true to stringResource(R.string.common_on),
  )
  Segments(
    options = options,
    isSelected = { it == enabled },
    labelOf = { options.first { p -> p.first == it }.second },
    onSelect = onSelect,
  )
}

@Composable
internal fun FullRefreshIntervalSegments(
  interval: Int,
  onSelect: (Int) -> Unit,
) {
  val options = listOf(
    0 to stringResource(R.string.common_off),
    1 to "1",
    3 to "3",
    5 to "5",
    10 to "10",
  )
  Segments(
    options = options,
    isSelected = { it == interval },
    labelOf = { options.first { p -> p.first == it }.second },
    onSelect = onSelect,
  )
}

@Composable
internal fun <T> Segments(
  options: List<Pair<T, String>>,
  isSelected: (T) -> Boolean,
  labelOf: (T) -> String,
  onSelect: (T) -> Unit,
) {
  val typography = LocalPanelyInkTypography.current
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .height(48.dp),
    horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    options.forEach { (value, _) ->
      val selected = isSelected(value)
      val bg = if (selected) PanelyInkColors.Ink else PanelyInkColors.Paper
      val fg = if (selected) PanelyInkColors.Paper else PanelyInkColors.Ink
      Box(
        modifier = Modifier
          .weight(1f)
          .fillMaxHeight()
          .background(bg)
          .border(2.dp, PanelyInkColors.Ink)
          .clickable(
            interactionSource = remember(value) { MutableInteractionSource() },
            indication = null,
            onClick = { onSelect(value) },
          ),
        contentAlignment = Alignment.Center,
      ) {
        Text(
          text = labelOf(value),
          style = typography.body,
          color = fg,
          textAlign = TextAlign.Center,
        )
      }
    }
  }
}
