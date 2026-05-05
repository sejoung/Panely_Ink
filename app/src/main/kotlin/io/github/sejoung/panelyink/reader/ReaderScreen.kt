package io.github.sejoung.panelyink.reader

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import io.github.sejoung.panelyink.library.LibraryEntry
import io.github.sejoung.panelyink.ui.theme.LocalPanelyInkSpacing
import io.github.sejoung.panelyink.ui.theme.LocalPanelyInkTypography
import io.github.sejoung.panelyink.ui.theme.PanelyInkColors

/**
 * 본문 리더 진입 화면.
 *
 * v1.0 M1.1 스코프: 책을 열어 첫 페이지를 표시한다. 페이지 이동·메뉴·세로 모드는
 * M1.2~M1.5 단계에서 점진 추가.
 *
 * 책을 못 여는 경우(아카이브 손상, 이미지 없음, IO 에러)는 Guidelines §9에 따라
 * 다이얼로그가 아닌 화면 중앙 inline 메시지 + 4dp Ink 보더로 강조한다.
 */
@Composable
fun ReaderScreen(
    entry: LibraryEntry,
    onBack: () -> Unit,
) {
    BackHandler { onBack() }

    val context = LocalContext.current.applicationContext
    val sessionState by produceState<SessionState>(SessionState.Loading, entry.documentUri) {
        value = try {
            val session = CbzBookSession.open(context, entry)
            session.decode(0) // M1.1: 첫 페이지를 보여주기 전 1장 사전 디코드
            SessionState.Ready(session)
        } catch (t: Throwable) {
            SessionState.Failed(t.message ?: "책을 여는 중 오류가 발생했습니다")
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(PanelyInkColors.Paper),
    ) {
        when (val s = sessionState) {
            SessionState.Loading -> ReaderLoading()
            is SessionState.Failed -> ReaderError(message = s.message)
            is SessionState.Ready -> ReaderContent(session = s.session)
        }
    }
}

@Composable
private fun ReaderContent(session: CbzBookSession) {
    DisposableEffect(session) {
        onDispose { session.close() }
    }
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx -> ReaderView(ctx).apply { attach(session) } },
        update = { view -> view.attach(session) },
    )
}

@Composable
private fun ReaderLoading() {
    val typography = LocalPanelyInkTypography.current
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = "로딩 중…",
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
