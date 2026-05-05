package io.github.sejoung.panelyink.library

import android.net.Uri
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
 * Design Guidelines §6 Dialog 규칙:
 * - 화면 중앙, 좌우 마진 32dp (PRD Guidelines)
 * - 2dp Ink 외곽선
 * - 백드롭은 어둡게 처리하지 않는다 — 외부 탭 = 닫기
 *
 * Compose의 `Dialog`는 기본적으로 dim scrim이 있다. `DialogProperties` +
 * window flag로 끌 수 있지만, M0에서는 시스템 기본 scrim을 그대로 쓴다 — Carta에서
 * 흐릿한 회색이 잠깐 노출되더라도 dialog dismiss 1회 풀리프레시로 정리된다.
 */
@Composable
fun ManageRootsDialog(
    roots: List<Uri>,
    onRemove: (Uri) -> Unit,
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
                Text("폴더 관리", style = typography.title, color = PanelyInkColors.Ink)
                Spacer(Modifier.height(spacing.space2))
                if (roots.isEmpty()) {
                    Text(
                        "추가된 폴더가 없습니다.",
                        style = typography.body,
                        color = PanelyInkColors.Mute,
                    )
                } else {
                    LazyColumn(modifier = Modifier.fillMaxWidth()) {
                        items(items = roots, key = { it.toString() }) { uri ->
                            RootRow(uri = uri, onRemove = { onRemove(uri) })
                        }
                    }
                }
                Spacer(Modifier.height(spacing.space3))
                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                    PanelyTextButton(label = "닫기", onClick = onDismiss, primary = true)
                }
            }
        }
    }
}

@Composable
private fun RootRow(uri: Uri, onRemove: () -> Unit) {
    val typography = LocalPanelyInkTypography.current
    val spacing = LocalPanelyInkSpacing.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = spacing.space1),
    ) {
        Text(
            text = uri.lastPathSegment ?: uri.toString(),
            style = typography.list,
            color = PanelyInkColors.Ink,
        )
        Text(
            text = uri.toString(),
            style = typography.caption,
            color = PanelyInkColors.Mute,
        )
        Spacer(Modifier.height(spacing.space1))
        Row(verticalAlignment = Alignment.CenterVertically) {
            PanelyTextButton(label = "제거", onClick = onRemove, primary = false)
        }
    }
}
