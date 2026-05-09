package io.github.sejoung.panelyink.reader

import android.content.Context
import android.content.ContextWrapper
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import io.github.sejoung.panelyink.MainActivity
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
import io.github.sejoung.panelyink.library.BookEntry
import io.github.sejoung.panelyink.ui.components.PanelyArrowBackIcon
import io.github.sejoung.panelyink.ui.components.PanelyArrowForwardIcon
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

    // Guidelines §12: 본문 모드는 크롬 0dp. 시스템 status/navigation bar를 숨겨
    // 페이지가 화면 전체를 차지하게 한다. 라이브러리 복귀 시 원상복귀.
    val view = LocalView.current
    val activity = LocalContext.current.findMainActivity()
    DisposableEffect(activity, view) {
        val window = activity?.window
        if (window != null) {
            val controller = WindowCompat.getInsetsController(window, view)
            // 스와이프로 일시 표시 — e-ink에서 잔상 가능성은 있지만, 사용자가
            // 의도적으로 시스템 바를 호출할 수 있어야 한다(예: 시간 확인).
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        }
        onDispose {
            if (window != null) {
                val controller = WindowCompat.getInsetsController(window, view)
                controller.show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }

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

    DisposableEffect(viewModel, session) {
        onDispose {
            viewModel.close()
            session.close()
        }
    }

    val state by viewModel.state.collectAsState()
    var view by remember { mutableStateOf<ReaderView?>(null) }
    var menuOpen by remember { mutableStateOf(false) }
    var settingsOpen by remember { mutableStateOf(false) }
    var bookmarkedPages by remember(session) { mutableStateOf<Set<Int>>(emptySet()) }
    val uiScope = rememberCoroutineScope()

    // ← back 우선순위: settings(자체 BackHandler) → menu → ReaderScreen 최상단(라이브러리로).
    // settingsOpen=true일 땐 ReaderSettingsScreen 안의 BackHandler가 잡으므로 여긴 비활성.
    BackHandler(enabled = menuOpen && !settingsOpen) {
        menuOpen = false
    }

    // 상태 → View 명령형 setter
    LaunchedEffect(
        state.currentPage,
        state.fitMode,
        state.trimEnabled,
        state.contrast,
        state.invertEnabled,
        view,
    ) {
        view?.setPageIndex(state.currentPage)
        view?.setFitMode(state.fitMode)
        view?.setTrimEnabled(state.trimEnabled)
        view?.setContrast(state.contrast)
        view?.setInvertEnabled(state.invertEnabled)
    }

    // PRD §6.1 Resume + M3 진행률 — 페이지 변경 시 (pageIndex, pageCount) 저장.
    // pageCount는 viewModel 생성 후 불변이라 매번 같은 값을 같이 넘김 → 라이브러리에서
    // 진행률 % 계산 가능.
    LaunchedEffect(state.currentPage, viewModel) {
        positionRepo.save(
            bookId = viewModel.bookId,
            pageIndex = state.currentPage,
            pageCount = viewModel.pageCount,
        )
    }

    // 책별 설정 저장 — fit/방향/트림/대비. 첫 진입 한 번 발화는 동일 값 저장이라 무해.
    LaunchedEffect(
        viewModel,
        state.fitMode,
        state.direction,
        state.trimEnabled,
        state.contrast,
    ) {
        bookSettingsRepo.save(
            bookId = viewModel.bookId,
            settings = BookSettings(
                fitMode = state.fitMode,
                direction = state.direction,
                trimEnabled = state.trimEnabled,
                contrast = state.contrast,
            ),
        )
    }

    // 전역 prefs 저장 — 흑백 반전 / 풀리프레시 주기. 책 메뉴에서 변경되어도 모든
    // 책에 같은 값을 적용하기 위해 SharedPreferences로 분리(2026-05-08).
    LaunchedEffect(viewModel, state.invertEnabled) {
        appPrefsRepo.setInvertEnabled(state.invertEnabled)
    }
    LaunchedEffect(viewModel, state.fullRefreshInterval) {
        appPrefsRepo.setFullRefreshInterval(state.fullRefreshInterval)
    }

    // 디코드 1장 도착 → invalidate. 현재 페이지가 캐시 hit이면 onDraw가 그림.
    LaunchedEffect(viewModel, view) {
        viewModel.decoded.collect { view?.invalidate() }
    }

    LaunchedEffect(session) {
        bookmarkedPages = bookmarkRepo.loadPages(session.bookId).toSet()
    }

    // 풀리프레시 generation 변경 — N페이지마다 검정 1프레임으로 잔상 정리(M2).
    // 첫 진입 시 generation=0 → LaunchedEffect 발화는 일어나지만 0 → 분기로 무시.
    LaunchedEffect(state.fullRefreshGeneration, view) {
        if (state.fullRefreshGeneration > 0) {
            view?.requestFullRefresh()
        }
    }

    // 하드웨어 키 라우팅 — 메뉴/설정 열림 시 키는 그 화면 닫기로 흡수.
    val activity = LocalContext.current.findMainActivity()
    DisposableEffect(
        activity,
        viewModel,
        menuOpen,
        settingsOpen,
        state.currentPage,
        context.previousBook,
        context.nextBook,
    ) {
        activity?.keyDispatcher = { event ->
            when {
                settingsOpen -> ReaderInput.dispatch(
                    event = event,
                    onPrev = { settingsOpen = false },
                    onNext = { settingsOpen = false },
                )
                menuOpen -> ReaderInput.dispatch(
                    event = event,
                    onPrev = { menuOpen = false },
                    onNext = { menuOpen = false },
                )
                else -> ReaderInput.dispatch(
                    event = event,
                    onPrev = {
                        val previousBook = context.previousBook
                        if (state.currentPage == 0 && previousBook != null) {
                            onNavigate(previousBook, state.toBookSettings())
                        } else {
                            viewModel.goPrevious()
                        }
                    },
                    onNext = {
                        val nextBook = context.nextBook
                        if (state.currentPage == viewModel.pageCount - 1 && nextBook != null) {
                            onNavigate(nextBook, state.toBookSettings())
                        } else {
                            viewModel.goNext()
                        }
                    },
                )
            }
        }
        onDispose { activity?.keyDispatcher = null }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        key(session) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    ReaderView(ctx).also {
                        it.attach(session, viewModel)
                        view = it
                    }
                },
                onRelease = { v ->
                    v.detach()
                    if (view === v) view = null
                },
            )
        }
        // 본문 위 레이어: 메뉴 닫혔으면 탭 영역, 열렸으면 메뉴 패널.
        if (!menuOpen) {
            TapRegions(
                directionIsRtl = state.direction == ReadingDirection.Rtl,
                onPrev = viewModel::goPrevious,
                onNext = viewModel::goNext,
                onMenu = { menuOpen = true },
            )
            val previousBook = context.previousBook
            val nextBook = context.nextBook
            if (
                nextBook != null &&
                state.currentPage == viewModel.pageCount - 1 &&
                !settingsOpen
            ) {
                NextBookCard(
                    title = nextBook.displayName,
                    onClick = {
                        onNavigate(nextBook, state.toBookSettings())
                    },
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            } else if (
                previousBook != null &&
                state.currentPage == 0 &&
                !settingsOpen
            ) {
                AdjacentBookCard(
                    label = "이전 권",
                    title = previousBook.displayName,
                    forward = false,
                    onClick = {
                        onNavigate(previousBook, state.toBookSettings())
                    },
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }
        } else {
            ReaderMenu(
                state = state,
                bookTitle = bookTitle,
                pageCount = viewModel.pageCount,
                previousBookTitle = context.previousBook?.displayName,
                nextBookTitle = context.nextBook?.displayName,
                currentPageBookmarked = state.currentPage in bookmarkedPages,
                onJumpToPage = { page ->
                    viewModel.goTo(page)
                    menuOpen = false
                },
                onPreviousBook = {
                    context.previousBook?.let { onNavigate(it, state.toBookSettings()) }
                },
                onNextBook = {
                    context.nextBook?.let { onNavigate(it, state.toBookSettings()) }
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
                onOpenSettings = {
                    settingsOpen = true
                },
                onExitToLibrary = {
                    menuOpen = false
                    onExit()
                },
                onClose = { menuOpen = false },
            )
        }
        // 풀스크린 설정 — 메뉴/탭 영역 위에 그려져 본문도 가린다.
        // 본문 ViewModel은 그대로 살아있어 설정 변경이 즉시 state에 반영되며,
        // 설정 닫힐 때 본문은 새 설정으로 이미 렌더돼있다.
        if (settingsOpen) {
            ReaderSettingsScreen(
                state = state,
                onFitModeChange = viewModel::setFitMode,
                onDirectionChange = viewModel::setDirection,
                onTrimEnabledChange = viewModel::setTrimEnabled,
                onContrastChange = viewModel::setContrast,
                onInvertEnabledChange = viewModel::setInvertEnabled,
                onTriggerFullRefresh = viewModel::triggerFullRefresh,
                onBack = {
                    settingsOpen = false
                    // 메뉴는 자동으로 닫아 본문으로 바로 복귀 — 사용자가 다시 메뉴
                    // 열고 싶으면 중앙 탭으로.
                    menuOpen = false
                },
            )
        }
    }
}

@Composable
private fun AdjacentBookCard(
    label: String,
    title: String,
    forward: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val typography = LocalPanelyInkTypography.current
    val spacing = LocalPanelyInkSpacing.current

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(PanelyInkColors.Paper)
            .border(2.dp, PanelyInkColors.Ink)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = spacing.space3, vertical = spacing.space2),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.space2),
    ) {
        if (!forward) PanelyArrowBackIcon(tint = PanelyInkColors.Ink)
        Text(
            text = label,
            style = typography.body,
            color = PanelyInkColors.Ink,
        )
        Text(
            text = title,
            style = typography.caption,
            color = PanelyInkColors.Mute,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (forward) PanelyArrowForwardIcon(tint = PanelyInkColors.Ink)
    }
}

@Composable
private fun NextBookCard(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AdjacentBookCard(
        label = "다음 권",
        title = title,
        forward = true,
        onClick = onClick,
        modifier = modifier,
    )
}

private fun ReaderState.toBookSettings(): BookSettings = BookSettings(
    fitMode = fitMode,
    direction = direction,
    trimEnabled = trimEnabled,
    contrast = contrast,
)

@Composable
private fun TapRegions(
    directionIsRtl: Boolean,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onMenu: () -> Unit,
) {
    // Guidelines §5: 좌 30 / 중앙 40 / 우 30. RTL이면 좌/우 의미 반전 (탭 한정).
    val left: () -> Unit = if (directionIsRtl) onNext else onPrev
    val right: () -> Unit = if (directionIsRtl) onPrev else onNext

    Row(
        modifier = Modifier.fillMaxSize(),
        horizontalArrangement = Arrangement.Start,
    ) {
        TapRegion(weight = 0.3f, onClick = left)
        TapRegion(weight = 0.4f, onClick = onMenu)
        TapRegion(weight = 0.3f, onClick = right)
    }
}

@Composable
private fun RowScope.TapRegion(weight: Float, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .weight(weight)
            .fillMaxHeight()
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
            ),
    )
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

/** ContextWrapper 체인을 따라 올라가 [MainActivity]를 찾는다. Compose의
 *  `LocalContext.current`가 ContextThemeWrapper로 감싸져 와도 안전. */
private tailrec fun Context.findMainActivity(): MainActivity? = when (this) {
    is MainActivity -> this
    is ContextWrapper -> baseContext.findMainActivity()
    else -> null
}
