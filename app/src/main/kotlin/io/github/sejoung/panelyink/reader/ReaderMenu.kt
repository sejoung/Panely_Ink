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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
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
import androidx.compose.material3.Text
import io.github.sejoung.panelyink.core.fit.FitMode
import io.github.sejoung.panelyink.ui.components.PanelyTextButton
import io.github.sejoung.panelyink.ui.theme.LocalPanelyInkSpacing
import io.github.sejoung.panelyink.ui.theme.LocalPanelyInkTypography
import io.github.sejoung.panelyink.ui.theme.PanelyInkColors
import kotlin.math.roundToInt

/**
 * 본문 리더의 최소 메뉴(M1.3). 중앙 탭으로 호출되며 화면 하단에 패널로 등장한다.
 *
 * Design Guidelines §6 / §12 / §11 준수:
 * - Card/Panel 규칙: Paper 배경 + 2dp Ink 보더, elevation/round/shadow 없음
 * - Slider 규칙: 트랙 4dp Hairline, 채워진 부분 4dp Ink, 24dp 정사각형 Ink 핸들,
 *   드래그 중 본문 페이지 변경 금지(=손을 떼야 적용)
 * - 백드롭 어둡게 처리하지 않음. 패널 외부 탭 = 닫기
 * - 메뉴 자체는 비-본문 크롬이므로 LTR 유지
 *
 * 책임:
 * - 현재 [ReaderState]를 받아 fit/direction 표시
 * - 사용자 조작을 콜백으로 위임 (ViewModel 직참조 X)
 *
 * 비책임:
 * - 메뉴 노출/숨김 상태 관리 → [ReaderScreen]
 * - 페이지 점프 검증/clamp → [ReaderViewModel.goTo]
 */
@Composable
fun ReaderMenu(
    state: ReaderState,
    pageCount: Int,
    onFitModeChange: (FitMode) -> Unit,
    onDirectionChange: (ReadingDirection) -> Unit,
    onJumpToPage: (Int) -> Unit,
    onExitToLibrary: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val typography = LocalPanelyInkTypography.current
    val spacing = LocalPanelyInkSpacing.current

    Box(modifier = modifier.fillMaxSize()) {
        // 백드롭 — 어둡게 처리 안 함(잔상 방지). 패널 밖 탭 = 닫기.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onClose,
                ),
        )

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .wrapContentHeight()
                .background(PanelyInkColors.Paper)
                .border(2.dp, PanelyInkColors.Ink)
                // 패널 내부 빈 영역 탭이 백드롭으로 새지 않도록 흡수.
                .pointerInput(Unit) { detectTapGestures { } }
                .padding(horizontal = spacing.space3, vertical = spacing.space2),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "메뉴",
                    style = typography.title,
                    color = PanelyInkColors.Ink,
                    modifier = Modifier.weight(1f),
                )
                PanelyTextButton(
                    label = "라이브러리로",
                    onClick = onExitToLibrary,
                    primary = false,
                )
            }
            Spacer(Modifier.height(spacing.space2))

            SectionLabel("화면 맞춤")
            Spacer(Modifier.height(spacing.space1))
            FitSegments(
                selected = state.fitMode,
                onSelect = onFitModeChange,
            )

            Spacer(Modifier.height(spacing.space2))

            SectionLabel("읽기 방향")
            Spacer(Modifier.height(spacing.space1))
            DirectionSegments(
                selected = state.direction,
                onSelect = onDirectionChange,
            )

            Spacer(Modifier.height(spacing.space2))

            SectionLabel("페이지 점프")
            Spacer(Modifier.height(spacing.space1))
            PageJump(
                pageCount = pageCount,
                currentPage = state.currentPage,
                onCommit = onJumpToPage,
            )
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

@Composable
private fun FitSegments(
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
private fun DirectionSegments(
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

/**
 * 가로로 나란한 토글. 선택된 항목은 fill 반전(Guidelines §7).
 *
 * `PanelyButton`을 weight로 채우려면 RowScope의 weight 모디파이어가 필요한데
 * 외부에서 weight 받기가 어색해, 여기서 인라인으로 동등한 시각 규칙으로 그린다.
 */
@Composable
private fun <T> Segments(
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
 * 페이지 점프 슬라이더 + 라벨.
 *
 * Guidelines §6 Slider 규칙:
 * - 트랙 4dp Hairline
 * - 채워진 부분 4dp Ink
 * - 핸들 24dp 정사각형 Ink (둥근 핸들 X)
 * - 드래그 중 라이브 프리뷰 금지 — 손을 떼야 적용 (디코드 폭주/잔상 방지)
 *
 * 라벨은 드래그 중 점프 대상 페이지를 미리 보여준다(라이브 프리뷰가 아니라
 * 단순 숫자 표시이므로 허용).
 */
@Composable
private fun PageJump(
    pageCount: Int,
    currentPage: Int,
    onCommit: (Int) -> Unit,
) {
    val typography = LocalPanelyInkTypography.current
    val density = LocalDensity.current

    val handleDp = 24.dp
    val handlePx = with(density) { handleDp.toPx() }
    val trackDp = 4.dp
    val totalDp = 48.dp

    var widthPx by remember { mutableStateOf(0) }
    // dragX는 컨테이너 내부의 손가락 x좌표(px). null이면 드래그 중 아님.
    var dragX by remember(currentPage, pageCount) { mutableStateOf<Float?>(null) }

    val maxThumbPx = (widthPx - handlePx).coerceAtLeast(0f)
    val baseRatio = if (pageCount <= 1) 0f
        else (currentPage.toFloat() / (pageCount - 1).toFloat()).coerceIn(0f, 1f)
    val baseThumbPx = baseRatio * maxThumbPx

    fun pointerToThumbPx(pointerX: Float): Float =
        (pointerX - handlePx / 2f).coerceIn(0f, maxThumbPx)

    fun pointerToPage(pointerX: Float): Int {
        if (pageCount <= 1) return 0
        if (maxThumbPx <= 0f) return 0
        val anchor = pointerToThumbPx(pointerX)
        return (anchor / maxThumbPx * (pageCount - 1)).roundToInt()
            .coerceIn(0, pageCount - 1)
    }

    val thumbPx = dragX?.let(::pointerToThumbPx) ?: baseThumbPx
    val thumbDp = with(density) { thumbPx.toDp() }
    val previewPage = dragX?.let(::pointerToPage) ?: currentPage

    Column(modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(totalDp)
                .onSizeChanged { widthPx = it.width }
                .pointerInput(pageCount) {
                    detectHorizontalDragGestures(
                        onDragStart = { offset -> dragX = offset.x },
                        onDragEnd = {
                            val finalX = dragX
                            dragX = null
                            if (finalX != null) {
                                val page = pointerToPage(finalX)
                                if (page != currentPage) onCommit(page)
                            }
                        },
                        onDragCancel = { dragX = null },
                        onHorizontalDrag = { change, _ -> dragX = change.position.x },
                    )
                }
                .pointerInput(pageCount) {
                    detectTapGestures(
                        onTap = { offset ->
                            val page = pointerToPage(offset.x)
                            if (page != currentPage) onCommit(page)
                        },
                    )
                },
        ) {
            // 트랙(Hairline) — 핸들 양 끝 반만큼 좌우 패딩해서 핸들 끝 정렬
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .fillMaxWidth()
                    .padding(horizontal = handleDp / 2)
                    .height(trackDp)
                    .offset(y = (totalDp - trackDp) / 2)
                    .background(PanelyInkColors.Hairline),
            )
            // 채워진 부분(Ink) — 트랙 좌측에서 핸들 중앙까지
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = handleDp / 2)
                    .width(thumbDp)
                    .height(trackDp)
                    .background(PanelyInkColors.Ink),
            )
            // 핸들(24dp 정사각 Ink)
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .offset(x = thumbDp)
                    .size(handleDp)
                    .background(PanelyInkColors.Ink),
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = "${previewPage + 1} / $pageCount",
            style = typography.caption,
            color = PanelyInkColors.Ink,
        )
    }
}
