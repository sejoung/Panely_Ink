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
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import io.github.sejoung.panelyink.MainActivity
import io.github.sejoung.panelyink.core.position.PositionKey
import io.github.sejoung.panelyink.core.position.PositionRepository
import io.github.sejoung.panelyink.core.position.SharedPreferencesPositionRepository
import io.github.sejoung.panelyink.library.BookEntry
import io.github.sejoung.panelyink.ui.theme.LocalPanelyInkSpacing
import io.github.sejoung.panelyink.ui.theme.LocalPanelyInkTypography
import io.github.sejoung.panelyink.ui.theme.PanelyInkColors

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
    entry: BookEntry,
    onBack: () -> Unit,
) {
    val appContext = LocalContext.current.applicationContext
    val positionRepo: PositionRepository = remember(appContext) {
        SharedPreferencesPositionRepository(appContext)
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
    val sessionState by produceState<SessionState>(SessionState.Loading, entry.documentUri) {
        value = try {
            Log.d(TAG, "ReaderScreen open: ${entry.displayName}")
            loadingStep = "페이지 목록 스캔 중…"
            val session = CbzBookSession.open(appContext, entry)
            loadingStep = "첫 페이지 디코드 중… (${session.pageCount}쪽)"
            session.decode(0)
            Log.d(TAG, "ReaderScreen ready")
            SessionState.Ready(session)
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
                positionRepo = positionRepo,
                onExit = onBack,
            )
        }
    }
}

private const val TAG = "PanelyInk.Reader"

@Composable
private fun ReaderContent(
    session: CbzBookSession,
    positionRepo: PositionRepository,
    onExit: () -> Unit,
) {
    // ViewModelStore에 캐시되지 않도록 remember(session)로 묶는다 — 책을 닫고
    // 다시 열 때 stale decoder(닫힌 ZipFile)를 잡는 문제 방지.
    val viewModel = remember(session) {
        // PRD §6.1 Resume: 마지막 페이지 복원. 페이지 수가 줄어든 책에 대비해 clamp.
        val resumed = positionRepo.load(session.bookId)
            ?.coerceIn(0, session.pageCount - 1)
            ?: 0
        ReaderViewModel(
            bookId = session.bookId,
            pageCount = session.pageCount,
            decoder = session.asDecoder(),
            initialPage = resumed,
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

    // PRD §6.1 Resume — 페이지 변경 시 저장. SharedPreferences.apply는 비동기
    // 디스크 쓰기를 합치므로 페이지 이동 빈도(초당 수 회)에서도 부담 없다.
    LaunchedEffect(state.currentPage, viewModel) {
        positionRepo.save(PositionKey(viewModel.bookId, state.currentPage))
    }

    // 디코드 1장 도착 → invalidate. 현재 페이지가 캐시 hit이면 onDraw가 그림.
    LaunchedEffect(viewModel, view) {
        viewModel.decoded.collect { view?.invalidate() }
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
    DisposableEffect(activity, viewModel, menuOpen, settingsOpen) {
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
                    onPrev = viewModel::goPrevious,
                    onNext = viewModel::goNext,
                )
            }
        }
        onDispose { activity?.keyDispatcher = null }
    }

    Box(modifier = Modifier.fillMaxSize()) {
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
        // 본문 위 레이어: 메뉴 닫혔으면 탭 영역, 열렸으면 메뉴 패널.
        if (!menuOpen) {
            TapRegions(
                directionIsRtl = state.direction == ReadingDirection.Rtl,
                onPrev = viewModel::goPrevious,
                onNext = viewModel::goNext,
                onMenu = { menuOpen = true },
            )
        } else {
            ReaderMenu(
                state = state,
                pageCount = viewModel.pageCount,
                onJumpToPage = { page ->
                    viewModel.goTo(page)
                    menuOpen = false
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
                onFullRefreshIntervalChange = viewModel::setFullRefreshInterval,
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
    data class Ready(val session: CbzBookSession) : SessionState
    data class Failed(val message: String) : SessionState
}

/** ContextWrapper 체인을 따라 올라가 [MainActivity]를 찾는다. Compose의
 *  `LocalContext.current`가 ContextThemeWrapper로 감싸져 와도 안전. */
private tailrec fun Context.findMainActivity(): MainActivity? = when (this) {
    is MainActivity -> this
    is ContextWrapper -> baseContext.findMainActivity()
    else -> null
}
