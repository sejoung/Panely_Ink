package io.github.sejoung.panelyink.reader.ui

import io.github.sejoung.panelyink.reader.ReaderState
import io.github.sejoung.panelyink.reader.ReaderViewModel
import io.github.sejoung.panelyink.reader.input.ReaderInput
import io.github.sejoung.panelyink.reader.model.BookSettings
import io.github.sejoung.panelyink.reader.model.ReadingDirection
import io.github.sejoung.panelyink.reader.model.SeriesContext
import io.github.sejoung.panelyink.reader.session.CbzBookSession
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.sejoung.panelyink.core.position.PositionRepository
import io.github.sejoung.panelyink.core.preferences.AppPreferences
import io.github.sejoung.panelyink.core.preferences.AppPreferencesRepository
import io.github.sejoung.panelyink.data.db.BookmarkRepository
import io.github.sejoung.panelyink.data.db.BookSettingsRepository
import io.github.sejoung.panelyink.data.db.PanelyDatabase
import io.github.sejoung.panelyink.data.db.RoomBookmarkRepository
import io.github.sejoung.panelyink.data.db.RoomBookSettingsRepository
import io.github.sejoung.panelyink.data.db.RoomPositionRepository
import io.github.sejoung.panelyink.data.preferences.SharedPrefsAppPreferencesRepository
import io.github.sejoung.panelyink.library.model.BookEntry
import io.github.sejoung.panelyink.ui.theme.LocalPanelyInkSpacing
import io.github.sejoung.panelyink.ui.theme.LocalPanelyInkTypography
import io.github.sejoung.panelyink.ui.theme.PanelyInkColors
import kotlinx.coroutines.launch

/**
 * 본문 리더 진입 화면.
 *
 * v1.0 M1.2 스코프:
 * - 책을 열어 페이지를 표시
 * - 3분할 탭 영역 (좌 30 / 중앙 40 / 우 30) + LTR/RTL 자동 반전 — Guidelines §5
 * - 하드웨어 키(볼륨/Page Up·Down/DPad) — [ReaderInput]
 *
 * 메뉴(중앙 탭)와 fit 변경 UI는 M1.3에서 채움.
 */
@Composable
fun ReaderScreen(
    context: SeriesContext,
    propagatedBookSettings: BookSettings? = null,
    onBack: () -> Unit,
    /**
     * 시리즈 형제 권으로 이동 — 호스트가 새 [SeriesContext]를 만들어 [ReaderScreen]을 다시
     * 부르는 식으로 처리. M4 시리즈 연속 읽기 카드 / "다음 권" 카드에서 사용.
     * 인자로 받은 [BookEntry]는 [SeriesContext.siblings] 안에 있어야 의미가 있다.
     */
    onNavigate: (BookEntry, BookSettings) -> Unit = { _, _ -> },
) {
    val entry = context.current
    val appContext = LocalContext.current.applicationContext
    val db = remember(appContext) { PanelyDatabase.getInstance(appContext) }
    val positionRepo: PositionRepository = remember(db) { RoomPositionRepository(db) }
    val bookSettingsRepo: BookSettingsRepository = remember(db) { RoomBookSettingsRepository(db) }
    val bookmarkRepo: BookmarkRepository = remember(db) { RoomBookmarkRepository(db) }
    val appPrefsRepo: AppPreferencesRepository = remember(appContext) {
        SharedPrefsAppPreferencesRepository(appContext)
    }
    BackHandler { onBack() }

    ReaderSystemBarsEffect()

    var loadingStep by remember { mutableStateOf("책을 여는 중…") }
    // produceState 키는 nested 책까지 구분하는 [BookEntry.bookIdSource]를 사용 — 일반 책은
    // documentUri와 동일, ZIP-of-CBZ 자식은 `${uri}#${entry}`라 형제 권 간 이동 시 새 책으로 정확히 재진입.
    val sessionState by produceState<SessionState>(SessionState.Loading, entry.bookIdSource) {
        value = try {
            Log.d(TAG, "ReaderScreen open: ${entry.displayName}")
            loadingStep = "페이지 목록 스캔 중…"
            val session = CbzBookSession.open(appContext, entry)
            loadingStep = "위치 복원 중…"
            // Room에서 마지막 페이지를 미리 로드 — ReaderViewModel 생성 시점이 동기라
            // 여기서 비동기로 받아 Ready에 묶어서 넘긴다. 페이지 수 줄어든 책은 clamp.
            val resumed = positionRepo.load(session.bookId)
                ?.pageIndex
                ?.coerceIn(0, session.pageCount - 1)
                ?: 0
            // 책별 설정도 같이 로드 — 없으면 DEFAULTS. 책당 1행이라 가벼움.
            val bookSettings = bookSettingsRepo.load(session.bookId)
                ?: propagatedBookSettings
                ?: BookSettings.DEFAULTS
            // 전역 prefs(흑백 반전, 풀리프레시 주기) — SharedPreferences 1회 read.
            val appPrefs = appPrefsRepo.load()
            loadingStep = "첫 페이지 디코드 중… (${session.pageCount}쪽)"
            session.decode(resumed)
            Log.d(TAG, "ReaderScreen ready (resume page=$resumed)")
            SessionState.Ready(
                session = session,
                resumedPage = resumed,
                bookSettings = bookSettings,
                appPreferences = appPrefs,
            )
        } catch (cancel: kotlinx.coroutines.CancellationException) {
            // 코루틴 취소는 다시 던져야 한다 — Failed로 잡으면 화면이 잘못 표시됨
            throw cancel
        } catch (t: Throwable) {
            Log.e(TAG, "ReaderScreen open failed: ${entry.displayName}", t)
            val cls = t.javaClass.simpleName
            val msg = t.message ?: "(메시지 없음)"
            SessionState.Failed("$cls: $msg")
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(PanelyInkColors.Paper),
    ) {
        when (val s = sessionState) {
            SessionState.Loading -> ReaderLoading(loadingStep)
            is SessionState.Failed -> ReaderError(message = s.message)
            is SessionState.Ready -> ReaderContent(
                session = s.session,
                bookTitle = entry.displayName,
                resumedPage = s.resumedPage,
                initialBookSettings = s.bookSettings,
                initialAppPreferences = s.appPreferences,
                positionRepo = positionRepo,
                bookSettingsRepo = bookSettingsRepo,
                bookmarkRepo = bookmarkRepo,
                appPrefsRepo = appPrefsRepo,
                context = context,
                onNavigate = onNavigate,
                onExit = onBack,
            )
        }
    }
}

private const val TAG = "PanelyInk.Reader"

@Composable
private fun ReaderContent(
    session: CbzBookSession,
    bookTitle: String,
    resumedPage: Int,
    initialBookSettings: BookSettings,
    initialAppPreferences: AppPreferences,
    positionRepo: PositionRepository,
    bookSettingsRepo: BookSettingsRepository,
    bookmarkRepo: BookmarkRepository,
    appPrefsRepo: AppPreferencesRepository,
    context: SeriesContext,
    onNavigate: (BookEntry, BookSettings) -> Unit,
    onExit: () -> Unit,
) {
    // ViewModelStore에 캐시되지 않도록 remember(session)로 묶는다 — 책을 닫고
    // 다시 열 때 stale decoder(닫힌 ZipFile)를 잡는 문제 방지.
    val viewModel = remember(session) {
        // 마지막 페이지/책별 설정/전역 prefs는 produceState 안에서 이미 로드됨.
        ReaderViewModel(
            bookId = session.bookId,
            pageCount = session.pageCount,
            decoder = session.asDecoder(),
            initialPage = resumedPage,
            initialBookSettings = initialBookSettings,
            initialAppPreferences = initialAppPreferences,
        )
    }

    ReaderLifecycleEffect(session = session, viewModel = viewModel)

    val state by viewModel.state.collectAsState()
    var view by remember { mutableStateOf<ReaderView?>(null) }
    var menuOpen by remember { mutableStateOf(false) }
    var settingsOpen by remember { mutableStateOf(false) }
    var bookmarkedPages by remember(session) { mutableStateOf<Set<Int>>(emptySet()) }
    val uiScope = rememberCoroutineScope()

    ReaderBackHandler(
        menuOpen = menuOpen,
        settingsOpen = settingsOpen,
        onCloseMenu = { menuOpen = false },
    )
    ReaderViewEffects(state = state, viewModel = viewModel, view = view)
    ReaderPersistenceEffects(
        state = state,
        viewModel = viewModel,
        positionRepo = positionRepo,
        bookSettingsRepo = bookSettingsRepo,
        appPrefsRepo = appPrefsRepo,
    )
    LaunchedEffect(session) {
        bookmarkedPages = bookmarkRepo.loadPages(session.bookId).toSet()
    }
    ReaderHardwareKeyHandler(
        state = state,
        viewModel = viewModel,
        context = context,
        menuOpen = menuOpen,
        settingsOpen = settingsOpen,
        onCloseMenu = { menuOpen = false },
        onCloseSettings = { settingsOpen = false },
        onNavigate = onNavigate,
    )

    Box(modifier = Modifier.fillMaxSize()) {
        ReaderAndroidViewHost(
            session = session,
            viewModel = viewModel,
            onViewAttached = { view = it },
            onViewReleased = { released ->
                if (view === released) view = null
            },
        )
        ReaderOverlayLayer(
            state = state,
            viewModel = viewModel,
            context = context,
            bookTitle = bookTitle,
            menuOpen = menuOpen,
            settingsOpen = settingsOpen,
            currentPageBookmarked = state.currentPage in bookmarkedPages,
            onOpenMenu = { menuOpen = true },
            onCloseMenu = { menuOpen = false },
            onOpenSettings = { settingsOpen = true },
            onCloseSettings = { settingsOpen = false },
            onExit = onExit,
            onJumpToPage = { page ->
                viewModel.goTo(page)
                menuOpen = false
            },
            onToggleBookmark = {
                val page = state.currentPage
                uiScope.launch {
                    if (page in bookmarkedPages) {
                        bookmarkRepo.remove(session.bookId, page)
                        bookmarkedPages = bookmarkedPages - page
                    } else {
                        bookmarkRepo.add(session.bookId, page)
                        bookmarkedPages = bookmarkedPages + page
                    }
                }
            },
            onNavigate = onNavigate,
        )
    }
}

@Composable
private fun ReaderLoading(message: String) {
    val typography = LocalPanelyInkTypography.current
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = message,
            style = typography.body,
            color = PanelyInkColors.Ink,
        )
    }
}

@Composable
private fun ReaderError(message: String) {
    val typography = LocalPanelyInkTypography.current
    val spacing = LocalPanelyInkSpacing.current
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(spacing.space5),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .border(4.dp, PanelyInkColors.Ink)
                .padding(spacing.space3),
        ) {
            Text(
                text = message,
                style = typography.body,
                color = PanelyInkColors.Ink,
                textAlign = TextAlign.Center,
            )
        }
    }
}

private sealed interface SessionState {
    data object Loading : SessionState
    data class Ready(
        val session: CbzBookSession,
        val resumedPage: Int,
        val bookSettings: BookSettings,
        val appPreferences: AppPreferences,
    ) : SessionState
    data class Failed(val message: String) : SessionState
}
