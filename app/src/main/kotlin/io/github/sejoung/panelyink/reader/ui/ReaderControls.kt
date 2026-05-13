package io.github.sejoung.panelyink.reader.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.sejoung.panelyink.R
import io.github.sejoung.panelyink.core.fit.FitMode
import io.github.sejoung.panelyink.core.render.ContrastMatrix
import io.github.sejoung.panelyink.ui.components.PanelyTextButton
import io.github.sejoung.panelyink.ui.components.Segments
import io.github.sejoung.panelyink.ui.theme.LocalPanelyInkTypography
import io.github.sejoung.panelyink.ui.theme.PanelyInkColors
import kotlin.math.roundToInt

/**
 * 본문 메뉴([ReaderMenu])와 설정 화면([ReaderSettingsScreen])이 공유하는 컨트롤
 * 컴포저블 모음. 둘 다 같은 ReaderState/콜백 모델을 쓰므로 시각/동작 규칙 일관성을
 * 위해 한 곳에서 정의.
 *
 * Design Guidelines §6/§7 준수:
 * - 세그먼트 선택 = fill 반전(Ink 배경 + Paper 텍스트)
 * - 슬라이더 트랙 4dp Hairline / fill 4dp Ink / 24dp 정사각 Ink 핸들
 * - 슬라이더 드래그 중 본문 변경 X — 손을 떼야 commit
 */

/**
 * 설정 그룹의 상위 헤더(예: "페이지 레이아웃", "화질"). [SectionLabel]은 한 그룹
 * 안의 개별 섹션 라벨이므로 위계상 더 옅게 표현 — 두 단계 hierarchy.
 */
@Composable
internal fun SectionLabel(text: String) {
  val typography = LocalPanelyInkTypography.current
  Text(
    text = text,
    style = typography.caption,
    color = PanelyInkColors.Mute,
  )
}

@Composable
internal fun FitSegments(
  selected: FitMode,
  onSelect: (FitMode) -> Unit,
) {
  val options = listOf(
    FitMode.FitScreen to stringResource(R.string.reader_fit_screen),
    FitMode.FitWidth to stringResource(R.string.reader_fit_width),
    FitMode.FitHeight to stringResource(R.string.reader_fit_height),
  )
  Segments(
    options = options,
    isSelected = { it == selected },
    labelOf = { options.first { p -> p.first == it }.second },
    onSelect = onSelect,
  )
}

@Composable
internal fun TrimSegments(
  enabled: Boolean,
  onSelect: (Boolean) -> Unit,
) {
  val options = listOf(
    true to stringResource(R.string.reader_trim_auto),
    false to stringResource(R.string.common_off),
  )
  Segments(
    options = options,
    isSelected = { it == enabled },
    labelOf = { options.first { p -> p.first == it }.second },
    onSelect = onSelect,
  )
}

/**
 * 대비 슬라이더 — 0.5..2.0 범위, 5% 스냅. 손 떼야 [onCommit].
 *
 * "원본" 버튼으로 1.0 즉시 복귀.
 */
@Composable
internal fun ContrastSlider(
  contrast: Float,
  onCommit: (Float) -> Unit,
  onReset: () -> Unit,
) {
  val typography = LocalPanelyInkTypography.current
  val density = LocalDensity.current

  val handleDp = 24.dp
  val handlePx = with(density) { handleDp.toPx() }
  val trackDp = 4.dp
  val totalDp = 48.dp

  var widthPx by remember { mutableStateOf(0) }
  var dragX by remember(contrast) { mutableStateOf<Float?>(null) }

  val maxThumbPx = (widthPx - handlePx).coerceAtLeast(0f)
  val range = ContrastMatrix.MAX - ContrastMatrix.MIN
  val baseRatio = ((contrast - ContrastMatrix.MIN) / range).coerceIn(0f, 1f)
  val baseThumbPx = baseRatio * maxThumbPx

  fun pointerToThumbPx(pointerX: Float): Float =
    (pointerX - handlePx / 2f).coerceIn(0f, maxThumbPx)

  fun pointerToContrast(pointerX: Float): Float {
    if (maxThumbPx <= 0f) return ContrastMatrix.IDENTITY
    val anchor = pointerToThumbPx(pointerX)
    val raw = ContrastMatrix.MIN + (anchor / maxThumbPx) * range
    return (Math.round(raw * 20f) / 20f).coerceIn(ContrastMatrix.MIN, ContrastMatrix.MAX)
  }

  val thumbPx = dragX?.let(::pointerToThumbPx) ?: baseThumbPx
  val thumbDp = with(density) { thumbPx.toDp() }
  val previewContrast = dragX?.let(::pointerToContrast) ?: contrast

  Column(modifier = Modifier.fillMaxWidth()) {
    Box(
      modifier = Modifier
        .fillMaxWidth()
        .height(totalDp)
        .onSizeChanged { widthPx = it.width }
        // tap과 drag를 단일 gesture loop로 통합 — Compose가 두 detector 사이의
        // 충돌을 임의로 깨뜨릴 위험을 없애고, 사용자가 누른 그 순간부터 drag로
        // 자연스럽게 이어지는 동작을 보장. tap은 "이동 없는 drag"의 특수 케이스로 처리.
        .pointerInput(Unit) {
          awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            dragX = down.position.x
            while (true) {
              val event = awaitPointerEvent()
              val change = event.changes.firstOrNull { it.id == down.id } ?: break
              dragX = change.position.x
              change.consume()
              if (!change.pressed) break
            }
            val finalX = dragX
            dragX = null
            if (finalX != null) {
              val v = pointerToContrast(finalX)
              if (v != contrast) onCommit(v)
            }
          }
        },
    ) {
      Box(
        modifier = Modifier
          .align(Alignment.CenterStart)
          .fillMaxWidth()
          .padding(horizontal = handleDp / 2)
          .height(trackDp)
          .offset(y = (totalDp - trackDp) / 2)
          .background(PanelyInkColors.Hairline),
      )
      Box(
        modifier = Modifier
          .align(Alignment.CenterStart)
          .padding(start = handleDp / 2)
          .width(thumbDp)
          .height(trackDp)
          .background(PanelyInkColors.Ink),
      )
      Box(
        modifier = Modifier
          .align(Alignment.CenterStart)
          .offset(x = thumbDp)
          .size(handleDp)
          .background(PanelyInkColors.Ink),
      )
    }
    Spacer(Modifier.height(4.dp))
    Row(
      modifier = Modifier.fillMaxWidth(),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(
        text = "${(previewContrast * 100).roundToInt()}%",
        style = typography.caption,
        color = PanelyInkColors.Ink,
        modifier = Modifier.weight(1f),
      )
      PanelyTextButton(
        label = stringResource(R.string.common_original),
        onClick = onReset,
        primary = false,
      )
    }
  }
}
