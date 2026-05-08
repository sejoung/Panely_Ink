package io.github.sejoung.panelyink.library

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.sejoung.panelyink.R
import io.github.sejoung.panelyink.ui.components.PanelyIconButton
import io.github.sejoung.panelyink.ui.components.PanelyMenuIcon
import io.github.sejoung.panelyink.ui.components.PanelyPlusIcon
import io.github.sejoung.panelyink.ui.theme.LocalPanelyInkSpacing
import io.github.sejoung.panelyink.ui.theme.LocalPanelyInkTypography
import io.github.sejoung.panelyink.ui.theme.PanelyInkColors

/**
 * 라이브러리 진입 화면. PRD §6.1 라이브러리 + Design Guidelines §6 리스트 / §5 안전 영역.
 *
 * v1.0 M0 스코프: 폴더 추가/제거 + depth-1 CBZ 목록.
 * 책 행 탭은 아직 동작하지 않는다 — 본문 뷰어는 M1.
 */
@Composable
fun LibraryScreen(
    viewModel: LibraryViewModel = viewModel(),
    onOpenBook: (LibraryEntry) -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var manageOpen by remember { mutableStateOf(false) }

    val pickFolder = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { uri -> if (uri != null) viewModel.addRoot(uri) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(PanelyInkColors.Paper)
            .windowInsetsPadding(WindowInsets.systemBars),
    ) {
        LibraryHeader(
            scanning = state.scanning,
            onAdd = { pickFolder.launch(null) },
            onManage = { manageOpen = true },
        )
        HairlineDivider()
        if (state.entries.isEmpty()) {
            LibraryEmptyState(
                hasRoots = state.roots.isNotEmpty(),
                scanning = state.scanning,
            )
        } else {
            LibraryList(state.entries, onOpenBook = onOpenBook)
        }
    }

    if (manageOpen) {
        ManageRootsDialog(
            roots = state.roots,
            onRemove = viewModel::removeRoot,
            onDismiss = { manageOpen = false },
        )
    }
}

@Composable
private fun LibraryHeader(
    scanning: Boolean,
    onAdd: () -> Unit,
    onManage: () -> Unit,
) {
    val typography = LocalPanelyInkTypography.current
    val spacing = LocalPanelyInkSpacing.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = spacing.space4, vertical = spacing.space2),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = stringResourceCompat(R.string.library_title),
                style = typography.title,
                color = PanelyInkColors.Ink,
            )
            if (scanning) {
                Text(
                    text = stringResourceCompat(R.string.library_scanning),
                    style = typography.caption,
                    color = PanelyInkColors.Mute,
                )
            }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.space1),
        ) {
            PanelyIconButton(onClick = onManage, primary = false) { tint ->
                PanelyMenuIcon(tint = tint)
            }
            PanelyIconButton(onClick = onAdd, primary = true) { tint ->
                PanelyPlusIcon(tint = tint)
            }
        }
    }
}

@Composable
private fun LibraryList(
    entries: List<LibraryEntry>,
    onOpenBook: (LibraryEntry) -> Unit,
) {
    val typography = LocalPanelyInkTypography.current
    val spacing = LocalPanelyInkSpacing.current
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(items = entries, key = { it.documentUri.toString() }) { entry ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onOpenBook(entry) }
                    .padding(horizontal = spacing.space5, vertical = spacing.space2),
            ) {
                Text(
                    text = entry.displayName,
                    style = typography.list,
                    color = PanelyInkColors.Ink,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = formatBytes(entry.sizeBytes),
                    style = typography.caption,
                    color = PanelyInkColors.Mute,
                )
            }
            HairlineDivider()
        }
    }
}

@Composable
private fun LibraryEmptyState(
    hasRoots: Boolean,
    scanning: Boolean,
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
                hasRoots -> "폴더에서 .cbz / .zip 파일을 찾지 못했습니다"
                else -> stringResourceCompat(R.string.library_empty_title)
            }
            val body = when {
                scanning -> ""
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
        }
    }
}

@Composable
private fun HairlineDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(PanelyInkColors.Hairline)
    )
}

/**
 * `androidx.compose.ui.res.stringResource` 의존을 줄이기 위한 작은 래퍼.
 * 직접 호출해도 무방하지만 Preview가 R 클래스를 못 찾을 때 대체점을 두기 쉽다.
 */
@Composable
private fun stringResourceCompat(id: Int): String =
    androidx.compose.ui.res.stringResource(id)

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "—"
    val mb = bytes / 1024.0 / 1024.0
    return if (mb >= 1.0) "%.1f MB".format(mb) else "%d KB".format(bytes / 1024)
}
