package io.github.sejoung.panelyink.library.ui

import io.github.sejoung.panelyink.library.LibraryViewModel
import io.github.sejoung.panelyink.library.model.BookEntry
import io.github.sejoung.panelyink.library.model.FolderEntry
import io.github.sejoung.panelyink.library.model.LibraryEntry
import io.github.sejoung.panelyink.library.model.SortMode
import io.github.sejoung.panelyink.library.model.ViewMode
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.sejoung.panelyink.reader.model.SeriesContext
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
    onOpenBook: (SeriesContext) -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var sortOpen by remember { mutableStateOf(false) }
    var searchActive by remember { mutableStateOf(false) }
    var appSettingsOpen by remember { mutableStateOf(false) }

    // ZIP-of-CBZ 검사 후 분기: 시리즈면 가상 폴더 진입, 일반이면 reader. PRD §6.1.
    // siblings은 클릭 시점의 visibleEntries에서 BookEntry만 — 가상 폴더 push 후 nested 책
    // 클릭 시는 nestedBooks가 그대로 형제로 들어가 reader가 시리즈 연속 기능에 사용.
    val onOpenBookSafe: (BookEntry, List<BookEntry>) -> Unit = { book, siblings ->
        viewModel.openBook(book) { resolved ->
            onOpenBook(SeriesContext(current = resolved, siblings = siblings))
        }
    }

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
            onRefresh = viewModel::refresh,
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
            else -> {
                // 시리즈 그룹핑 — 폴더 행에 첫 책의 표지를 lookup. folderFirstBook + covers
                // 두 단계로 같은 비트맵 재사용(메모리 중복 X). 빈 폴더는 매핑 없음.
                val folderCovers = remember(state.folderFirstBook, state.covers) {
                    buildMap<android.net.Uri, ImageBitmap> {
                        state.folderFirstBook.forEach { (folderUri, bookId) ->
                            state.covers[bookId]?.let { put(folderUri, it) }
                        }
                    }
                }
                when (state.viewMode) {
                    ViewMode.List, ViewMode.Cover -> LibraryList(
                        entries = visibleEntries,
                        viewMode = state.viewMode,
                        folderCounts = state.folderCounts,
                        folderCovers = folderCovers,
                        covers = state.covers,
                        bookProgress = state.bookProgress,
                        onEnterFolder = viewModel::enterFolder,
                        onOpenBook = onOpenBookSafe,
                        onRequestCount = viewModel::requestFolderCount,
                        onRequestCover = viewModel::requestCover,
                        onRequestFolderCover = viewModel::requestFolderCover,
                        onRequestProgress = viewModel::requestProgress,
                    )
                    ViewMode.Grid -> LibraryGrid(
                        entries = visibleEntries,
                        folderCounts = state.folderCounts,
                        folderCovers = folderCovers,
                        covers = state.covers,
                        bookProgress = state.bookProgress,
                        onEnterFolder = viewModel::enterFolder,
                        onOpenBook = onOpenBookSafe,
                        onRequestCount = viewModel::requestFolderCount,
                        onRequestCover = viewModel::requestCover,
                        onRequestFolderCover = viewModel::requestFolderCover,
                        onRequestProgress = viewModel::requestProgress,
                    )
                }
            }
        }
    }

    if (appSettingsOpen) {
        AppSettingsScreen(
            roots = state.roots,
            onAddRoot = onAddFolder,
            onRemoveRoot = viewModel::removeRoot,
            onClearCoverCache = viewModel::clearCoverCache,
            onResetAllData = {
                viewModel.resetAllData()
                appSettingsOpen = false
            },
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
