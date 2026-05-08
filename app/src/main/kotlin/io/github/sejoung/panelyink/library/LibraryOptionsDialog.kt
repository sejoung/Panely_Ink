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
 * 라이브러리 보기 옵션 다이얼로그 — 정렬과 표시 모드를 한 화면에서 조정.
 *
 * 두 차원을 분리하지 않고 묶은 이유:
 * - 사용자 인지: "라이브러리 보기 방식"으로 통합 인지가 자연스럽다
 * - 헤더 아이콘 폭주 방지(정렬/표시 두 아이콘 → 한 아이콘)
 * - 정렬 변경과 표시 변경이 종종 같이 일어남(예: 그리드 + 최근 본 책)
 *
 * Guidelines §6 다이얼로그 규칙: Paper 배경 + 2dp Ink 보더, 백드롭 dim 없음.
 */
@Composable
fun LibraryOptionsDialog(
    selectedSort: SortMode,
    selectedView: ViewMode,
    onSortChange: (SortMode) -> Unit,
    onViewChange: (ViewMode) -> Unit,
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
                    text = "보기 옵션",
                    style = typography.title,
                    color = PanelyInkColors.Ink,
                )

                Spacer(Modifier.height(spacing.space2))

                SectionLabel("정렬")
                Spacer(Modifier.height(spacing.space1))
                OptionRow(
                    label = "이름순",
                    isSelected = selectedSort == SortMode.Name,
                    onClick = { onSortChange(SortMode.Name) },
                )
                Spacer(Modifier.height(spacing.space1))
                OptionRow(
                    label = "최근 본 책",
                    isSelected = selectedSort == SortMode.LastOpened,
                    onClick = { onSortChange(SortMode.LastOpened) },
                )

                Spacer(Modifier.height(spacing.space2))

                SectionLabel("표시")
                Spacer(Modifier.height(spacing.space1))
                OptionRow(
                    label = "목록",
                    isSelected = selectedView == ViewMode.List,
                    onClick = { onViewChange(ViewMode.List) },
                )
                Spacer(Modifier.height(spacing.space1))
                OptionRow(
                    label = "표지",
                    isSelected = selectedView == ViewMode.Cover,
                    onClick = { onViewChange(ViewMode.Cover) },
                )
                Spacer(Modifier.height(spacing.space1))
                OptionRow(
                    label = "그리드",
                    isSelected = selectedView == ViewMode.Grid,
                    onClick = { onViewChange(ViewMode.Grid) },
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
private fun SectionLabel(text: String) {
    val typography = LocalPanelyInkTypography.current
    Text(
        text = text,
        style = typography.caption,
        color = PanelyInkColors.Mute,
    )
}

@Composable
private fun OptionRow(
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
