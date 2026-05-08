package io.github.sejoung.panelyink.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.sejoung.panelyink.core.fit.FitMode
import io.github.sejoung.panelyink.core.render.ContrastMatrix
import io.github.sejoung.panelyink.ui.components.PanelyTextButton
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
        FitMode.FitScreen to "화면",
        FitMode.FitWidth to "가로",
        FitMode.FitHeight to "세로",
    )
    Segments(
        options = options,
        isSelected = { it == selected },
        labelOf = { options.first { p -> p.first == it }.second },
        onSelect = onSelect,
    )
}

@Composable
internal fun DirectionSegments(
    selected: ReadingDirection,
    onSelect: (ReadingDirection) -> Unit,
) {
    // M1.5에서 VerticalScroll 추가. 지금은 LTR/RTL 두 개만 노출.
    val options = listOf(
        ReadingDirection.Ltr to "좌→우",
        ReadingDirection.Rtl to "우→좌",
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
        true to "자동",
        false to "끔",
    )
    Segments(
        options = options,
        isSelected = { it == enabled },
        labelOf = { options.first { p -> p.first == it }.second },
        onSelect = onSelect,
    )
}

@Composable
internal fun FullRefreshIntervalSegments(
    interval: Int,
    onSelect: (Int) -> Unit,
) {
    // 0=끔, 1=매 페이지, 3/5/10=일반. 1과 10은 잔상 체감 비교용 양쪽 끝.
    val options = listOf(
        1 to "1",
        3 to "3",
        5 to "5",
        10 to "10",
        0 to "끔",
    )
    Segments(
        options = options,
        isSelected = { it == interval },
        labelOf = { options.first { p -> p.first == it }.second },
        onSelect = onSelect,
    )
}

/**
 * 가로로 나란한 토글. 선택된 항목은 fill 반전(Guidelines §7).
 *
 * `PanelyButton`을 weight로 채우려면 RowScope의 weight 모디파이어가 필요한데
 * 외부에서 weight 받기가 어색해, 여기서 인라인으로 동등한 시각 규칙으로 그린다.
 */
@Composable
internal fun <T> Segments(
    options: List<Pair<T, String>>,
    isSelected: (T) -> Boolean,
    labelOf: (T) -> String,
    onSelect: (T) -> Unit,
) {
    val typography = LocalPanelyInkTypography.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEach { (value, _) ->
            val selected = isSelected(value)
            val bg = if (selected) PanelyInkColors.Ink else PanelyInkColors.Paper
            val fg = if (selected) PanelyInkColors.Paper else PanelyInkColors.Ink
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .background(bg)
                    .border(2.dp, PanelyInkColors.Ink)
                    .clickable(
                        interactionSource = remember(value) { MutableInteractionSource() },
                        indication = null,
                        onClick = { onSelect(value) },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = labelOf(value),
                    style = typography.body,
                    color = fg,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
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
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragStart = { offset -> dragX = offset.x },
                        onDragEnd = {
                            val finalX = dragX
                            dragX = null
                            if (finalX != null) {
                                val v = pointerToContrast(finalX)
                                if (v != contrast) onCommit(v)
                            }
                        },
                        onDragCancel = { dragX = null },
                        onHorizontalDrag = { change, _ -> dragX = change.position.x },
                    )
                }
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { offset ->
                            val v = pointerToContrast(offset.x)
                            if (v != contrast) onCommit(v)
                        },
                    )
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
                label = "원본",
                onClick = onReset,
                primary = false,
            )
        }
    }
}
