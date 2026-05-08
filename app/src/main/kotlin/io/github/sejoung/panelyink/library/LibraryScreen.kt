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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.DisposableEffect
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import io.github.sejoung.panelyink.core.position.BookProgress
import io.github.sejoung.panelyink.core.position.PositionKey
import kotlin.math.roundToInt
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.sejoung.panelyink.R
import io.github.sejoung.panelyink.ui.components.PanelyArrowBackIcon
import io.github.sejoung.panelyink.ui.components.PanelyBookIcon
import io.github.sejoung.panelyink.ui.components.PanelyChevronRightIcon
import io.github.sejoung.panelyink.ui.components.PanelyCloseIcon
import io.github.sejoung.panelyink.ui.components.PanelyFolderIcon
import io.github.sejoung.panelyink.ui.components.PanelyIconButton
import io.github.sejoung.panelyink.ui.components.PanelySearchIcon
import io.github.sejoung.panelyink.ui.components.PanelySettingsIcon
import io.github.sejoung.panelyink.ui.components.PanelySortIcon
import io.github.sejoung.panelyink.ui.components.PanelyTextButton
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
    var sortOpen by remember { mutableStateOf(false) }
    var searchActive by remember { mutableStateOf(false) }
    var appSettingsOpen by remember { mutableStateOf(false) }

    val pickFolder = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { uri -> if (uri != null) viewModel.addRoot(uri) }

    // 라이브러리 화면이 활성인 동안 시스템 바를 명시적으로 표시 + 사용자 스와이프로
    // 다시 부르도록 정책 설정. ReaderScreen에서 hide한 후 라이브러리로 복귀해도
    // 보장. SAF picker(별도 Activity)에 직접 영향은 못 주지만, 같은 Window 정책이
    // 일부 OEM 구현에서 picker에도 inherit될 수 있어 안전 장치.
    val view = LocalView.current
    val context = LocalContext.current
    DisposableEffect(view, context) {
        val activity = resolveActivity(context)
        activity?.window?.let { window ->
            val controller = WindowCompat.getInsetsController(window, view)
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
        onDispose { /* ReaderScreen 진입 시 거기서 다시 hide */ }
    }
    val onAddFolder: () -> Unit = {
        runCatching {
            val activity = resolveActivity(context)
            activity?.window?.let { window ->
                WindowCompat.getInsetsController(window, view)
                    .show(WindowInsetsCompat.Type.systemBars())
            }
        }
        // 마지막 추가 root 위치를 picker 시작 위치로 hint — picker 깊이를 줄여
        // 사용자가 ← 여러 번 안 눌러도 되게. 첫 추가 시(roots 비어있음)는 null로
        // 시스템 default 위치 사용.
        pickFolder.launch(state.roots.lastOrNull())
    }

    // 시스템 뒤로 키 우선순위: 검색 모드 → 폴더 위로 → 시스템 처리.
    BackHandler(enabled = searchActive) {
        viewModel.setSearchQuery("")
        searchActive = false
    }
    BackHandler(enabled = !searchActive && state.path.isNotEmpty()) { viewModel.goUp() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(PanelyInkColors.Paper)
            .windowInsetsPadding(WindowInsets.systemBars),
    ) {
        LibraryHeader(
            path = state.path,
            scanning = state.scanning,
            searchActive = searchActive,
            searchQuery = state.searchQuery,
            onActivateSearch = { searchActive = true },
            onCancelSearch = {
                viewModel.setSearchQuery("")
                searchActive = false
            },
            onSearchQueryChange = viewModel::setSearchQuery,
            onOpenSettings = { appSettingsOpen = true },
            onSort = { sortOpen = true },
            onUp = viewModel::goUp,
        )
        HairlineDivider()
        // 검색어 적용 후 보이는 항목. 빈 검색어면 raw entries 그대로.
        val visibleEntries = remember(state.entries, state.searchQuery) {
            val q = state.searchQuery.trim()
            if (q.isEmpty()) state.entries
            else state.entries.filter { it.displayName.contains(q, ignoreCase = true) }
        }
        when {
            state.entries.isEmpty() -> LibraryEmptyState(
                inFolder = state.path.isNotEmpty(),
                hasRoots = state.roots.isNotEmpty(),
                scanning = state.scanning,
                onAddFolder = onAddFolder,
            )
            visibleEntries.isEmpty() -> SearchEmptyState(query = state.searchQuery)
            else -> when (state.viewMode) {
                ViewMode.List, ViewMode.Cover -> LibraryList(
                    entries = visibleEntries,
                    viewMode = state.viewMode,
                    folderCounts = state.folderCounts,
                    covers = state.covers,
                    bookProgress = state.bookProgress,
                    onEnterFolder = viewModel::enterFolder,
                    onOpenBook = onOpenBook,
                    onRequestCount = viewModel::requestFolderCount,
                    onRequestCover = viewModel::requestCover,
                    onRequestProgress = viewModel::requestProgress,
                )
                ViewMode.Grid -> LibraryGrid(
                    entries = visibleEntries,
                    folderCounts = state.folderCounts,
                    covers = state.covers,
                    bookProgress = state.bookProgress,
                    onEnterFolder = viewModel::enterFolder,
                    onOpenBook = onOpenBook,
                    onRequestCount = viewModel::requestFolderCount,
                    onRequestCover = viewModel::requestCover,
                    onRequestProgress = viewModel::requestProgress,
                )
            }
        }
    }

    if (appSettingsOpen) {
        AppSettingsScreen(
            roots = state.roots,
            onAddRoot = onAddFolder,
            onRemoveRoot = viewModel::removeRoot,
            onClearCoverCache = viewModel::clearCoverCache,
            onBack = { appSettingsOpen = false },
        )
    }

    if (sortOpen) {
        LibraryOptionsDialog(
            selectedSort = state.sortMode,
            selectedView = state.viewMode,
            onSortChange = viewModel::setSortMode,
            onViewChange = viewModel::setViewMode,
            onDismiss = { sortOpen = false },
        )
    }
}

@Composable
private fun LibraryHeader(
    path: List<FolderEntry>,
    scanning: Boolean,
    searchActive: Boolean,
    searchQuery: String,
    onActivateSearch: () -> Unit,
    onCancelSearch: () -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onSort: () -> Unit,
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
        // 폴더 안/밖 모두 노출 — single-root 자동 진입 시에도 root 추가/관리 가능해야 함.
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
            // 폴더 추가는 설정 화면 / 빈 상태 가이드로 흡수(2026-05-08).
            // 한 번 설정 후 자주 안 쓰는 액션이라 헤더에서 제거 — 헤더 3아이콘.
            PanelyIconButton(onClick = onOpenSettings, primary = false) { tint ->
                PanelySettingsIcon(tint = tint)
            }
        }
    }
}

/**
 * 검색 모드 헤더 — [← 닫기] [검색 입력 필드 ____] [X 클리어].
 *
 * 자동 포커스 + 키보드 자동 표시. 검색 중엔 정렬/메뉴/추가 버튼은 숨겨 입력 영역 확보.
 * 입력 필드 외곽선은 1dp Hairline(Card 규칙) — 입력 자체가 명시 액션이라 강조 불필요.
 */
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
                keyboardActions = KeyboardActions(onSearch = { /* 그대로 — 입력 변경 시 즉시 필터 */ }),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester),
            )
            if (query.isEmpty()) {
                Text(
                    text = "이름으로 검색",
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
    viewMode: ViewMode,
    folderCounts: Map<android.net.Uri, Int>,
    covers: Map<String, ImageBitmap>,
    bookProgress: Map<String, BookProgress>,
    onEnterFolder: (FolderEntry) -> Unit,
    onOpenBook: (BookEntry) -> Unit,
    onRequestCount: (FolderEntry) -> Unit,
    onRequestCover: (BookEntry) -> Unit,
    onRequestProgress: (BookEntry) -> Unit,
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
                    when (viewMode) {
                        ViewMode.Cover -> BookRow(
                            entry = entry,
                            cover = covers[bookId],
                            progress = bookProgress[bookId],
                            onClick = { onOpenBook(entry) },
                            onRequestCover = onRequestCover,
                            onRequestProgress = onRequestProgress,
                        )
                        ViewMode.List -> BookRowCompact(
                            entry = entry,
                            progress = bookProgress[bookId],
                            onClick = { onOpenBook(entry) },
                            onRequestProgress = onRequestProgress,
                        )
                        // Grid는 LibraryGrid에서 처리. 여기 분기는 도달 X.
                        ViewMode.Grid -> Unit
                    }
                }
            }
            HairlineDivider()
        }
    }
}

/**
 * 텍스트 위주 한 줄 행 — [ViewMode.List]. 표지 추출 안 하므로 archive open 비용 0.
 * 진행률은 작은 우측 텍스트(% Mute)로만.
 */
@Composable
private fun BookRowCompact(
    entry: BookEntry,
    progress: BookProgress?,
    onClick: () -> Unit,
    onRequestProgress: (BookEntry) -> Unit,
) {
    val typography = LocalPanelyInkTypography.current
    val spacing = LocalPanelyInkSpacing.current

    LaunchedEffect(entry.documentUri) { onRequestProgress(entry) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = spacing.space4, vertical = spacing.space2),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.space2),
    ) {
        PanelyBookIcon()
        Text(
            text = entry.displayName,
            style = typography.list,
            color = PanelyInkColors.Ink,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (progress != null && progress.isKnown) {
            Text(
                text = "${(progress.percent * 100).roundToInt()}%",
                style = typography.caption,
                color = PanelyInkColors.Mute,
            )
        }
    }
}

@Composable
private fun LibraryGrid(
    entries: List<LibraryEntry>,
    folderCounts: Map<android.net.Uri, Int>,
    covers: Map<String, ImageBitmap>,
    bookProgress: Map<String, BookProgress>,
    onEnterFolder: (FolderEntry) -> Unit,
    onOpenBook: (BookEntry) -> Unit,
    onRequestCount: (FolderEntry) -> Unit,
    onRequestCover: (BookEntry) -> Unit,
    onRequestProgress: (BookEntry) -> Unit,
) {
    val spacing = LocalPanelyInkSpacing.current
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        modifier = Modifier
            .fillMaxSize()
            .padding(spacing.space2),
        verticalArrangement = Arrangement.spacedBy(spacing.space2),
        horizontalArrangement = Arrangement.spacedBy(spacing.space2),
    ) {
        items(items = entries, key = { it.documentUri.toString() }) { entry ->
            when (entry) {
                is FolderEntry -> FolderGridCell(
                    entry = entry,
                    count = folderCounts[entry.documentUri],
                    onClick = { onEnterFolder(entry) },
                    onRequestCount = onRequestCount,
                )
                is BookEntry -> {
                    val bookId = remember(entry.documentUri) {
                        PositionKey.bookIdFromUri(entry.documentUri.toString())
                    }
                    BookGridCell(
                        entry = entry,
                        cover = covers[bookId],
                        progress = bookProgress[bookId],
                        onClick = { onOpenBook(entry) },
                        onRequestCover = onRequestCover,
                        onRequestProgress = onRequestProgress,
                    )
                }
            }
        }
    }
}

@Composable
private fun BookGridCell(
    entry: BookEntry,
    cover: ImageBitmap?,
    progress: BookProgress?,
    onClick: () -> Unit,
    onRequestCover: (BookEntry) -> Unit,
    onRequestProgress: (BookEntry) -> Unit,
) {
    val typography = LocalPanelyInkTypography.current
    LaunchedEffect(entry.documentUri) {
        onRequestCover(entry)
        onRequestProgress(entry)
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(0.71f) // 만화 표지 4:5.6
                .background(PanelyInkColors.Paper)
                .border(1.dp, PanelyInkColors.Hairline),
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
            if (progress != null && progress.isKnown) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .background(PanelyInkColors.Paper)
                        .border(1.dp, PanelyInkColors.Ink)
                        .padding(horizontal = 4.dp, vertical = 1.dp),
                ) {
                    Text(
                        text = "${(progress.percent * 100).roundToInt()}%",
                        style = typography.caption,
                        color = PanelyInkColors.Ink,
                    )
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = entry.displayName,
            style = typography.body,
            color = PanelyInkColors.Ink,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun FolderGridCell(
    entry: FolderEntry,
    count: Int?,
    onClick: () -> Unit,
    onRequestCount: (FolderEntry) -> Unit,
) {
    val typography = LocalPanelyInkTypography.current
    LaunchedEffect(entry.documentUri) { onRequestCount(entry) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(0.71f)
                .background(PanelyInkColors.Paper)
                .border(1.dp, PanelyInkColors.Hairline),
            contentAlignment = Alignment.Center,
        ) {
            PanelyFolderIcon(size = 64.dp)
            if (count != null) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .background(PanelyInkColors.Paper)
                        .border(1.dp, PanelyInkColors.Ink)
                        .padding(horizontal = 4.dp, vertical = 1.dp),
                ) {
                    Text(
                        text = if (count == 0) "비어있음" else "${count}권",
                        style = typography.caption,
                        color = PanelyInkColors.Ink,
                    )
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = entry.displayName,
            style = typography.body,
            color = PanelyInkColors.Ink,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
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
    progress: BookProgress?,
    onClick: () -> Unit,
    onRequestCover: (BookEntry) -> Unit,
    onRequestProgress: (BookEntry) -> Unit,
) {
    val typography = LocalPanelyInkTypography.current
    val spacing = LocalPanelyInkSpacing.current

    // 행이 처음 컴포지션될 때 lazy 요청 — 표지/진행률 둘 다.
    LaunchedEffect(entry.documentUri) {
        onRequestCover(entry)
        onRequestProgress(entry)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = spacing.space4, vertical = spacing.space2),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.space2),
    ) {
        // 표지 자리 — 만화 비율 4:5.6에 가깝게 80×112dp. 미추출 시 책 아이콘 placeholder.
        // 우하단에 진행률 % 라벨(미열람 책은 미표시).
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
            if (progress != null && progress.isKnown) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .background(PanelyInkColors.Paper)
                        .border(1.dp, PanelyInkColors.Ink)
                        .padding(horizontal = 4.dp, vertical = 1.dp),
                ) {
                    Text(
                        text = "${(progress.percent * 100).roundToInt()}%",
                        style = typography.caption,
                        color = PanelyInkColors.Ink,
                    )
                }
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
            // 첫 사용자 가이드: root 0개 + 폴더 안 아닌 상태에서만 버튼 노출.
            // 폴더 안에선 ← back으로 위로 가는 게 자연스러움.
            if (!scanning && !inFolder) {
                Spacer(Modifier.height(spacing.space3))
                PanelyTextButton(
                    label = "폴더 추가",
                    onClick = onAddFolder,
                    primary = !hasRoots,
                )
                // SAF picker는 시스템 화면이라 우리가 X/취소 UI를 못 추가한다.
                // 디바이스마다 picker UI가 달라 사용자가 헷갈릴 수 있어 안내 캡션.
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
private fun SearchEmptyState(query: String) {
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

/**
 * Compose `LocalContext`는 ContextThemeWrapper로 감싸져 오기도 해 [android.app.Activity]
 * 가 즉시 안 나올 수 있다. ContextWrapper 체인을 따라 탐색.
 */
private tailrec fun resolveActivity(context: android.content.Context): android.app.Activity? =
    when (context) {
        is android.app.Activity -> context
        is android.content.ContextWrapper -> resolveActivity(context.baseContext)
        else -> null
    }
