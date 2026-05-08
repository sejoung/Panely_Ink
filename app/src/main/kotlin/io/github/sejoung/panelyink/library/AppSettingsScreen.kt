package io.github.sejoung.panelyink.library

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.sejoung.panelyink.core.preferences.AppPreferences
import io.github.sejoung.panelyink.core.preferences.AppPreferencesRepository
import io.github.sejoung.panelyink.data.preferences.SharedPrefsAppPreferencesRepository
import io.github.sejoung.panelyink.reader.FullRefreshIntervalSegments
import io.github.sejoung.panelyink.reader.GroupHeader
import io.github.sejoung.panelyink.reader.InvertSegments
import io.github.sejoung.panelyink.ui.components.PanelyArrowBackIcon
import io.github.sejoung.panelyink.ui.components.PanelyIconButton
import io.github.sejoung.panelyink.ui.components.PanelyTextButton
import io.github.sejoung.panelyink.ui.theme.LocalPanelyInkSpacing
import io.github.sejoung.panelyink.ui.theme.LocalPanelyInkTypography
import io.github.sejoung.panelyink.ui.theme.PanelyInkColors
import kotlinx.coroutines.launch

/**
 * 앱 전역 설정 화면 — 라이브러리에서 진입. 모든 책에 공통 적용되는 옵션만.
 *
 * 책별 설정([io.github.sejoung.panelyink.reader.ReaderSettingsScreen])과 분리:
 * - 디바이스 특성/환경 의존(풀리프레시 주기, 흑백 반전) → 여기
 * - 책 내용에 대한 사용자 취향(맞춤/방향/트리밍/대비) → 책 메뉴
 *
 * 변경 사항은 즉시 [AppPreferencesRepository]에 저장. 다음 책 진입에 반영.
 */
@Composable
fun AppSettingsScreen(
    roots: List<Uri>,
    onRemoveRoot: (Uri) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler { onBack() }

    val typography = LocalPanelyInkTypography.current
    val spacing = LocalPanelyInkSpacing.current
    val ctx = LocalContext.current.applicationContext
    val repo: AppPreferencesRepository = remember(ctx) {
        SharedPrefsAppPreferencesRepository(ctx)
    }
    val scope = rememberCoroutineScope()

    // 화면 진입 시 1회 SharedPreferences 로드. 책 진입 흐름과 달리 빈번하지 않아 단순.
    var prefs by remember { mutableStateOf<AppPreferences?>(null) }
    LaunchedEffect(repo) { prefs = repo.load() }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(PanelyInkColors.Paper),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = spacing.space3, vertical = spacing.space2),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.space2),
        ) {
            PanelyIconButton(onClick = onBack, primary = false) { tint ->
                PanelyArrowBackIcon(tint = tint)
            }
            Text(
                text = "앱 설정",
                style = typography.title,
                color = PanelyInkColors.Ink,
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(PanelyInkColors.Hairline),
        )

        val current = prefs
        if (current == null) {
            // 1회 로드 동안 placeholder. 거의 즉시 끝나서 시각적으로 거의 안 보임.
            Box(
                modifier = Modifier.fillMaxSize().padding(spacing.space3),
                contentAlignment = Alignment.Center,
            ) {
                Text(text = "로딩 중…", style = typography.body, color = PanelyInkColors.Mute)
            }
            return@Column
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = spacing.space3, vertical = spacing.space2),
        ) {
            GroupHeader("디스플레이")
            Spacer(Modifier.height(spacing.space2))

            SectionLabel("풀리프레시 주기 (페이지)")
            Spacer(Modifier.height(spacing.space1))
            FullRefreshIntervalSegments(
                interval = current.fullRefreshInterval,
                onSelect = { value ->
                    prefs = current.copy(fullRefreshInterval = value)
                    scope.launch { repo.setFullRefreshInterval(value) }
                },
            )

            Spacer(Modifier.height(spacing.space3))

            SectionLabel("흑백 반전")
            Spacer(Modifier.height(spacing.space1))
            InvertSegments(
                enabled = current.invertEnabled,
                onSelect = { value ->
                    prefs = current.copy(invertEnabled = value)
                    scope.launch { repo.setInvertEnabled(value) }
                },
            )

            Spacer(Modifier.height(spacing.space4))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(PanelyInkColors.Hairline),
            )
            Spacer(Modifier.height(spacing.space4))

            // 폴더 관리 — root 추가는 헤더 "+" 아이콘에서, 제거는 여기서.
            // 이전엔 별도 ManageRootsDialog였으나 단계 깊어서 통합(2026-05-08).
            GroupHeader("폴더")
            Spacer(Modifier.height(spacing.space2))
            if (roots.isEmpty()) {
                Text(
                    text = "추가된 폴더가 없습니다. 라이브러리 헤더의 + 버튼으로 추가하세요.",
                    style = typography.body,
                    color = PanelyInkColors.Mute,
                )
            } else {
                roots.forEach { uri ->
                    RootRow(uri = uri, onRemove = { onRemoveRoot(uri) })
                }
            }

            Spacer(Modifier.height(spacing.space3))
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
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = uri.toString(),
            style = typography.caption,
            color = PanelyInkColors.Mute,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(spacing.space1))
        Row(verticalAlignment = Alignment.CenterVertically) {
            PanelyTextButton(label = "제거", onClick = onRemove, primary = false)
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    val typography = LocalPanelyInkTypography.current
    Text(
        text = text,
        style = typography.caption,
        color = PanelyInkColors.Mute,
    )
}
