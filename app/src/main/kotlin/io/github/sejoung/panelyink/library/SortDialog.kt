package io.github.sejoung.panelyink.library

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.sejoung.panelyink.ui.components.PanelyTextButton
import io.github.sejoung.panelyink.ui.theme.LocalPanelyInkSpacing
import io.github.sejoung.panelyink.ui.theme.LocalPanelyInkTypography
import io.github.sejoung.panelyink.ui.theme.PanelyInkColors

/**
 * 정렬 모드 선택 다이얼로그 — Guidelines §6 Dialog 규칙(좌우 32dp 마진, 2dp Ink 보더,
 * 백드롭 dim 없음). 옵션 행 하나가 [SortMode] 하나에 1:1 매핑.
 *
 * 선택된 옵션은 fill 반전(§7) — Ink 배경 + Paper 텍스트.
 */
@Composable
fun SortDialog(
    selected: SortMode,
    onSelect: (SortMode) -> Unit,
    onDismiss: () -> Unit,
) {
    val typography = LocalPanelyInkTypography.current
    val spacing = LocalPanelyInkSpacing.current

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .padding(horizontal = spacing.space4)
                .background(PanelyInkColors.Paper)
                .border(2.dp, PanelyInkColors.Ink)
                .padding(spacing.space3),
        ) {
            Column {
                Text(
                    text = "정렬",
                    style = typography.title,
                    color = PanelyInkColors.Ink,
                )
                Spacer(Modifier.height(spacing.space2))
                SortOption(
                    label = "이름순",
                    isSelected = selected == SortMode.Name,
                    onClick = { onSelect(SortMode.Name) },
                )
                Spacer(Modifier.height(spacing.space1))
                SortOption(
                    label = "최근 본 책",
                    isSelected = selected == SortMode.LastOpened,
                    onClick = { onSelect(SortMode.LastOpened) },
                )
                Spacer(Modifier.height(spacing.space2))
                Row(
                    horizontalArrangement = Arrangement.End,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    PanelyTextButton(label = "닫기", onClick = onDismiss, primary = true)
                }
            }
        }
    }
}

@Composable
private fun SortOption(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    val typography = LocalPanelyInkTypography.current
    val bg = if (isSelected) PanelyInkColors.Ink else PanelyInkColors.Paper
    val fg = if (isSelected) PanelyInkColors.Paper else PanelyInkColors.Ink
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .background(bg)
            .border(2.dp, PanelyInkColors.Ink)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(text = label, style = typography.body, color = fg)
    }
}
