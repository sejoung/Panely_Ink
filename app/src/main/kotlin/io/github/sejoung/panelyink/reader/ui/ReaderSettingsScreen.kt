package io.github.sejoung.panelyink.reader.ui

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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.sejoung.panelyink.R
import io.github.sejoung.panelyink.core.fit.FitMode
import io.github.sejoung.panelyink.core.render.ContrastMatrix
import io.github.sejoung.panelyink.reader.ReaderState
import io.github.sejoung.panelyink.reader.model.ReadingDirection
import io.github.sejoung.panelyink.ui.components.PanelyArrowBackIcon
import io.github.sejoung.panelyink.ui.components.PanelyIconButton
import io.github.sejoung.panelyink.ui.components.PanelyTextButton
import io.github.sejoung.panelyink.ui.theme.LocalPanelyInkSpacing
import io.github.sejoung.panelyink.ui.theme.LocalPanelyInkTypography
import io.github.sejoung.panelyink.ui.theme.PanelyInkColors

/**
 * 본문 리더의 풀스크린 설정. [ReaderMenu]의 "설정 ⋯" 버튼이 진입.
 *
 * 분리 이유: 7" 화면에서 메뉴 패널 한 장에 8개 섹션을 쌓으면 본문 가독성이 사라진다.
 * 자주 쓰는 액션(페이지 점프/라이브러리 복귀)은 메뉴 패널에, 책당 1회 설정하는
 * 옵션(맞춤/방향/트림/대비/풀리프레시)은 풀스크린 설정에 둔다.
 *
 * 시각 규칙은 [ReaderControls]의 컴포저블에 위임 — 메뉴 패널과 일관된 모양.
 *
 * 시스템 바는 [ReaderScreen]의 `WindowInsetsController.hide`가 화면 진입 동안 계속
 * 적용되어 있으므로 별도 처리 없음.
 */
@Composable
fun ReaderSettingsScreen(
  state: ReaderState,
  onFitModeChange: (FitMode) -> Unit,
  onDirectionChange: (ReadingDirection) -> Unit,
  onTrimEnabledChange: (Boolean) -> Unit,
  onContrastChange: (Float) -> Unit,
  onInvertEnabledChange: (Boolean) -> Unit,
  onTriggerFullRefresh: () -> Unit,
  onBack: () -> Unit,
  modifier: Modifier = Modifier,
) {
  BackHandler { onBack() }

  val typography = LocalPanelyInkTypography.current
  val spacing = LocalPanelyInkSpacing.current

  Column(
    modifier = modifier
      .fillMaxSize()
      .background(PanelyInkColors.Paper),
  ) {
    // 헤더 — back 화살표 + 제목
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
        text = stringResource(R.string.reader_settings_title),
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

    // 본문 — 3그룹(페이지 레이아웃 / 화질 / 디스플레이)으로 묶고 Hairline divider로
    // 분리. 섹션 라벨은 caption Mute, 그룹 헤더는 list Ink로 시각 hierarchy.
    Column(
      modifier = Modifier
        .fillMaxSize()
        .verticalScroll(rememberScrollState())
        .padding(horizontal = spacing.space3, vertical = spacing.space2),
    ) {
      // ── 그룹 1: 페이지 레이아웃 ─────────────────────────────
      GroupHeader(stringResource(R.string.reader_group_page_layout))
      Spacer(Modifier.height(spacing.space2))

      SectionLabel(stringResource(R.string.reader_fit))
      Spacer(Modifier.height(spacing.space1))
      FitSegments(
        selected = state.fitMode,
        onSelect = onFitModeChange,
      )

      Spacer(Modifier.height(spacing.space2))

      SectionLabel(stringResource(R.string.reader_direction))
      Spacer(Modifier.height(spacing.space1))
      DirectionSegments(
        selected = state.direction,
        onSelect = onDirectionChange,
      )

      Spacer(Modifier.height(spacing.space2))

      SectionLabel(stringResource(R.string.reader_trim))
      Spacer(Modifier.height(spacing.space1))
      TrimSegments(
        enabled = state.trimEnabled,
        onSelect = onTrimEnabledChange,
      )

      GroupSeparator(spacing.space4)

      // ── 그룹 2: 화질 ────────────────────────────────────
      GroupHeader(stringResource(R.string.reader_group_image))
      Spacer(Modifier.height(spacing.space2))

      SectionLabel(stringResource(R.string.reader_contrast))
      Spacer(Modifier.height(spacing.space1))
      ContrastSlider(
        contrast = state.contrast,
        onCommit = onContrastChange,
        onReset = { onContrastChange(ContrastMatrix.IDENTITY) },
      )

      Spacer(Modifier.height(spacing.space2))

      SectionLabel(stringResource(R.string.settings_invert))
      Spacer(Modifier.height(spacing.space1))
      InvertSegments(
        enabled = state.invertEnabled,
        onSelect = onInvertEnabledChange,
      )

      GroupSeparator(spacing.space4)

      // ── 그룹 3: 디스플레이 (현재 책에 즉시 영향) ─────────
      // 풀리프레시 주기는 디바이스 특성이라 라이브러리 앱 설정에서 전역 관리.
      // 여기엔 "현재 책에 한 번 깜빡임"만 둠.
      GroupHeader(stringResource(R.string.reader_group_display))
      Spacer(Modifier.height(spacing.space2))

      PanelyTextButton(
        label = stringResource(R.string.reader_full_refresh_now),
        onClick = onTriggerFullRefresh,
        primary = false,
        modifier = Modifier.fillMaxWidth(),
      )

      Spacer(Modifier.height(spacing.space3))
    }
  }
}

/** 그룹 사이 큰 spacing + 1dp Hairline divider. */
@Composable
private fun GroupSeparator(verticalSpace: androidx.compose.ui.unit.Dp) {
  Spacer(Modifier.height(verticalSpace))
  Box(
    modifier = Modifier
      .fillMaxWidth()
      .height(1.dp)
      .background(PanelyInkColors.Hairline),
  )
  Spacer(Modifier.height(verticalSpace))
}
