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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.sejoung.panelyink.R
import io.github.sejoung.panelyink.ui.components.PanelyTextButton
import io.github.sejoung.panelyink.ui.theme.LocalPanelyInkSpacing
import io.github.sejoung.panelyink.ui.theme.LocalPanelyInkTypography
import io.github.sejoung.panelyink.ui.theme.PanelyInkColors

/**
 * "전체 초기화" 확인 다이얼로그 — 위험(되돌릴 수 없음) 액션이라 한 번 더 확인.
 *
 * Guidelines §6 다이얼로그 규칙: Paper 배경 + 2dp Ink 보더, 백드롭 dim 없음.
 *
 * 버튼 위치 결정:
 * - "취소" = primary(fill 반전) — 안전한 default
 * - "초기화" = secondary(보더만) — 위험 액션은 한 번 더 신중히 누르도록
 */
@Composable
fun ConfirmResetDialog(
    onConfirm: () -> Unit,
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
                    text = stringResource(R.string.settings_reset_title),
                    style = typography.title,
                    color = PanelyInkColors.Ink,
                )
                Spacer(Modifier.height(spacing.space2))
                Text(
                    text = stringResource(R.string.settings_reset_confirm_body),
                    style = typography.body,
                    color = PanelyInkColors.Ink,
                )
                Spacer(Modifier.height(spacing.space1))
                Text(
                    text = stringResource(R.string.settings_reset_confirm_warning),
                    style = typography.body,
                    color = PanelyInkColors.Mute,
                )
                Spacer(Modifier.height(spacing.space3))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(spacing.space1),
                ) {
                    PanelyTextButton(
                        label = stringResource(R.string.common_cancel),
                        onClick = onDismiss,
                        primary = true,
                        modifier = Modifier.weight(1f),
                    )
                    PanelyTextButton(
                        label = stringResource(R.string.common_reset),
                        onClick = onConfirm,
                        primary = false,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}
