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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import io.github.sejoung.panelyink.MainActivity
import io.github.sejoung.panelyink.library.LibraryEntry
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
    entry: LibraryEntry,
    onBack: () -> Unit,
) {
    BackHandler { onBack() }

    val context = LocalContext.current.applicationContext
    var loadingStep by remember { mutableStateOf("책을 여는 중…") }
    val sessionState by produceState<SessionState>(SessionState.Loading, entry.documentUri) {
        value = try {
            Log.d(TAG, "ReaderScreen open: ${entry.displayName}")
            loadingStep = "페이지 목록 스캔 중…"
            val session = CbzBookSession.open(context, entry)
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
            is SessionState.Ready -> ReaderContent(session = s.session)
        }
    }
}

private const val TAG = "PanelyInk.Reader"

@Composable
private fun ReaderContent(session: CbzBookSession) {
    // ViewModelStore에 캐시되지 않도록 remember(session)로 묶는다 — 책을 닫고
    // 다시 열 때 stale decoder(닫힌 ZipFile)를 잡는 문제 방지.
    val viewModel = remember(session) {
        ReaderViewModel(
            bookId = session.bookId,
            pageCount = session.pageCount,
            decoder = session.asDecoder(),
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

    // 상태 → View 명령형 setter
    LaunchedEffect(state.currentPage, state.fitMode, view) {
        view?.setPageIndex(state.currentPage)
        view?.setFitMode(state.fitMode)
    }

    // 디코드 1장 도착 → invalidate. 현재 페이지가 캐시 hit이면 onDraw가 그림.
    LaunchedEffect(viewModel, view) {
        viewModel.decoded.collect { view?.invalidate() }
    }

    // 하드웨어 키 라우팅 — Activity의 dispatchKeyEvent에서 가로채서
    // 시스템 볼륨 변동 같은 부작용 차단.
    val activity = LocalContext.current.findMainActivity()
    DisposableEffect(activity, viewModel) {
        activity?.keyDispatcher = { event ->
            ReaderInput.dispatch(
                event = event,
                onPrev = viewModel::goPrevious,
                onNext = viewModel::goNext,
            )
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
        TapRegions(
            directionIsRtl = state.direction == ReadingDirection.Rtl,
            onPrev = viewModel::goPrevious,
            onNext = viewModel::goNext,
            onMenu = { /* M1.3 메뉴 자리 */ },
        )
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
