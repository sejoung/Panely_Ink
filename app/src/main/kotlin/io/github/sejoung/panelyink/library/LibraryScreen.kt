package io.github.sejoung.panelyink.library

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.sejoung.panelyink.core.position.PositionKey
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.sejoung.panelyink.R
import io.github.sejoung.panelyink.ui.components.PanelyArrowBackIcon
import io.github.sejoung.panelyink.ui.components.PanelyBookIcon
import io.github.sejoung.panelyink.ui.components.PanelyChevronRightIcon
import io.github.sejoung.panelyink.ui.components.PanelyFolderIcon
import io.github.sejoung.panelyink.ui.components.PanelyIconButton
import io.github.sejoung.panelyink.ui.components.PanelyMenuIcon
import io.github.sejoung.panelyink.ui.components.PanelyPlusIcon
import io.github.sejoung.panelyink.ui.theme.LocalPanelyInkSpacing
import io.github.sejoung.panelyink.ui.theme.LocalPanelyInkTypography
import io.github.sejoung.panelyink.ui.theme.PanelyInkColors

/**
 * 라이브러리 진입 화면. 트리 탐색 모델 (1단계):
 * - 첫 화면: 사용자가 추가한 root 폴더들의 목록
 * - 폴더 탭 → 그 폴더의 직계 자식들로 이동(폴더 + 책)
 * - 책 탭 → 리더 진입
 * - 헤더의 ← / 시스템 뒤로 키 / breadcrumb root 탭 → 한 단계 위
 *
 * 화면 폭이 7"(1648×1236)라 좌측 트리 사이드바 대신 한 번에 한 디렉토리.
 */
@Composable
fun LibraryScreen(
    viewModel: LibraryViewModel = viewModel(),
    onOpenBook: (BookEntry) -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var manageOpen by remember { mutableStateOf(false) }

    val pickFolder = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { uri -> if (uri != null) viewModel.addRoot(uri) }

    // 폴더 안에 들어와 있을 땐 시스템 뒤로 키를 한 단계 위 이동에 쓴다.
    BackHandler(enabled = state.path.isNotEmpty()) { viewModel.goUp() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(PanelyInkColors.Paper)
            .windowInsetsPadding(WindowInsets.systemBars),
    ) {
        LibraryHeader(
            path = state.path,
            scanning = state.scanning,
            onAdd = { pickFolder.launch(null) },
            onManage = { manageOpen = true },
            onUp = viewModel::goUp,
        )
        HairlineDivider()
        if (state.entries.isEmpty()) {
            LibraryEmptyState(
                inFolder = state.path.isNotEmpty(),
                hasRoots = state.roots.isNotEmpty(),
                scanning = state.scanning,
            )
        } else {
            LibraryList(
                entries = state.entries,
                folderCounts = state.folderCounts,
                covers = state.covers,
                onEnterFolder = viewModel::enterFolder,
                onOpenBook = onOpenBook,
                onRequestCount = viewModel::requestFolderCount,
                onRequestCover = viewModel::requestCover,
            )
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
    path: List<FolderEntry>,
    scanning: Boolean,
    onAdd: () -> Unit,
    onManage: () -> Unit,
    onUp: () -> Boolean,
) {
    val typography = LocalPanelyInkTypography.current
    val spacing = LocalPanelyInkSpacing.current
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
        // 폴더 안/밖 모두 노출 — single-root 자동 진입 시에도 root 추가/관리 가능해야 함.
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

/** "라이브러리 ▸ root1 ▸ folderA" — 너무 길면 끝부분을 자른다. */
@Composable
private fun BreadcrumbLine(path: List<FolderEntry>) {
    val typography = LocalPanelyInkTypography.current
    val crumbs = listOf(stringResourceCompat(R.string.library_title)) +
        path.map { it.displayName }
    val text = crumbs.joinToString(separator = " ▸ ")
    Text(
        text = text,
        style = typography.caption,
        color = PanelyInkColors.Mute,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun LibraryList(
    entries: List<LibraryEntry>,
    folderCounts: Map<android.net.Uri, Int>,
    covers: Map<String, ImageBitmap>,
    onEnterFolder: (FolderEntry) -> Unit,
    onOpenBook: (BookEntry) -> Unit,
    onRequestCount: (FolderEntry) -> Unit,
    onRequestCover: (BookEntry) -> Unit,
) {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(items = entries, key = { it.documentUri.toString() }) { entry ->
            when (entry) {
                is FolderEntry -> FolderRow(
                    entry = entry,
                    count = folderCounts[entry.documentUri],
                    onClick = { onEnterFolder(entry) },
                    onRequestCount = onRequestCount,
                )
                is BookEntry -> {
                    val bookId = remember(entry.documentUri) {
                        PositionKey.bookIdFromUri(entry.documentUri.toString())
                    }
                    BookRow(
                        entry = entry,
                        cover = covers[bookId],
                        onClick = { onOpenBook(entry) },
                        onRequestCover = onRequestCover,
                    )
                }
            }
            HairlineDivider()
        }
    }
}

@Composable
private fun FolderRow(
    entry: FolderEntry,
    count: Int?,
    onClick: () -> Unit,
    onRequestCount: (FolderEntry) -> Unit,
) {
    val typography = LocalPanelyInkTypography.current
    val spacing = LocalPanelyInkSpacing.current

    // 행이 처음 컴포지션될 때 lazy 카운트 요청. 캐시 hit이면 ViewModel 쪽에서 즉시 반환.
    LaunchedEffect(entry.documentUri) { onRequestCount(entry) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = spacing.space4, vertical = spacing.space2),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.space2),
    ) {
        PanelyFolderIcon()
        Text(
            text = entry.displayName,
            style = typography.list,
            color = PanelyInkColors.Ink,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (count != null) {
            // 미계산 동안은 텍스트 자체를 그리지 않음(잔상 최소화). 도착하면 한 번만 표시.
            Text(
                text = if (count == 0) "비어있음" else "${count}권",
                style = typography.caption,
                color = PanelyInkColors.Mute,
            )
        }
        PanelyChevronRightIcon()
    }
}

@Composable
private fun BookRow(
    entry: BookEntry,
    cover: ImageBitmap?,
    onClick: () -> Unit,
    onRequestCover: (BookEntry) -> Unit,
) {
    val typography = LocalPanelyInkTypography.current
    val spacing = LocalPanelyInkSpacing.current

    // 행이 처음 컴포지션될 때 lazy 표지 요청. 캐시 hit이면 ViewModel이 즉시 반환.
    LaunchedEffect(entry.documentUri) { onRequestCover(entry) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = spacing.space4, vertical = spacing.space2),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.space2),
    ) {
        // 표지 자리 — 만화 비율 4:5.6에 가깝게 80×112dp. 미추출 시 책 아이콘 placeholder.
        Box(
            modifier = Modifier
                .size(width = 80.dp, height = 112.dp)
                .background(PanelyInkColors.Paper),
            contentAlignment = Alignment.Center,
        ) {
            if (cover != null) {
                Image(
                    bitmap = cover,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                PanelyBookIcon()
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.displayName,
                style = typography.list,
                color = PanelyInkColors.Ink,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = formatBytes(entry.sizeBytes),
                style = typography.caption,
                color = PanelyInkColors.Mute,
            )
        }
    }
}

@Composable
private fun LibraryEmptyState(
    inFolder: Boolean,
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

@Composable
private fun stringResourceCompat(id: Int): String =
    androidx.compose.ui.res.stringResource(id)

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "—"
    val mb = bytes / 1024.0 / 1024.0
    return if (mb >= 1.0) "%.1f MB".format(mb) else "%d KB".format(bytes / 1024)
}
