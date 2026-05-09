package io.github.sejoung.panelyink.reader.ui

import io.github.sejoung.panelyink.reader.ReaderState
import io.github.sejoung.panelyink.reader.ReaderViewModel
import io.github.sejoung.panelyink.reader.input.ReaderInput
import io.github.sejoung.panelyink.reader.model.BookSettings
import io.github.sejoung.panelyink.reader.model.ReadingDirection
import io.github.sejoung.panelyink.reader.model.SeriesContext
import io.github.sejoung.panelyink.reader.session.CbzBookSession
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow
import io.github.sejoung.panelyink.R
import io.github.sejoung.panelyink.ui.components.PanelyArrowBackIcon
import io.github.sejoung.panelyink.ui.components.PanelyArrowForwardIcon
import io.github.sejoung.panelyink.ui.components.PanelyIconButton
import io.github.sejoung.panelyink.ui.components.PanelyTextButton
import io.github.sejoung.panelyink.ui.theme.LocalPanelyInkSpacing
import io.github.sejoung.panelyink.ui.theme.LocalPanelyInkTypography
import io.github.sejoung.panelyink.ui.theme.PanelyInkColors
import kotlin.math.roundToInt

/**
 * 본문 리더의 빠른 메뉴 — 자주 쓰는 액션만. 중앙 탭으로 호출되며 화면 하단에
 * 작은 패널로 등장한다.
 *
 * 디자인 원칙(2026-05-08 결정):
 * - 자주 쓰는 = 페이지 점프, 라이브러리 복귀 → 여기
 * - 책당 1회 설정 = 화면 맞춤/방향/트림/대비/풀리프레시 → [ReaderSettingsScreen]
 *
 * Guidelines §6 / §12 / §11 준수:
 * - Card/Panel: Paper 배경 + 2dp Ink 보더, elevation/round/shadow 없음
 * - Slider: 트랙 4dp Hairline, 채워진 부분 4dp Ink, 24dp 정사각 핸들, 손 떼야 적용
 * - 백드롭 어둡게 처리 안 함. 패널 외부 탭 = 닫기
 *
 * 책임:
 * - 현재 [ReaderState]를 받아 페이지 점프 슬라이더 표시
 * - 라이브러리 복귀 / 설정 진입 / 페이지 점프를 콜백으로 위임
 *
 * 비책임:
 * - 메뉴 노출/숨김 상태 → [ReaderScreen]
 * - 페이지 점프 검증/clamp → [ReaderViewModel.goTo]
 */
@Composable
fun ReaderMenu(
    state: ReaderState,
    bookTitle: String,
    pageCount: Int,
    previousBookTitle: String?,
    nextBookTitle: String?,
    currentPageBookmarked: Boolean,
    onJumpToPage: (Int) -> Unit,
    onPreviousBook: () -> Unit,
    onNextBook: () -> Unit,
    onToggleBookmark: () -> Unit,
    onOpenSettings: () -> Unit,
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

        // 상단 헤더 — ← 라이브러리 / 책 제목 / 페이지 인디케이터.
        // 라이브러리/설정 화면과 같은 ← 좌상단 패턴으로 일관성. 메뉴 호출 전엔 본문
        // 풀스크린(Guidelines §12) — 헤더는 메뉴 호출 시에만 등장.
        Row(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .background(PanelyInkColors.Paper)
                .border(2.dp, PanelyInkColors.Ink)
                .pointerInput(Unit) { detectTapGestures { } }
                .padding(horizontal = spacing.space2, vertical = spacing.space1),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(
                spacing.space2,
            ),
        ) {
            PanelyIconButton(onClick = onExitToLibrary, primary = false) { tint ->
                PanelyArrowBackIcon(tint = tint)
            }
            Text(
                text = bookTitle,
                style = typography.title,
                color = PanelyInkColors.Ink,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            PanelyIconButton(
                onClick = onPreviousBook,
                primary = false,
                enabled = previousBookTitle != null,
            ) { tint ->
                PanelyArrowBackIcon(tint = tint)
            }
            PanelyIconButton(
                onClick = onNextBook,
                primary = false,
                enabled = nextBookTitle != null,
            ) { tint ->
                PanelyArrowForwardIcon(tint = tint)
            }
            Text(
                text = "${state.currentPage + 1} / $pageCount",
                style = typography.caption,
                color = PanelyInkColors.Mute,
            )
        }

        // 하단 패널 — 페이지 점프 + 설정 진입.
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
            SectionLabel(stringResource(R.string.reader_page_jump))
            Spacer(Modifier.height(spacing.space1))
            PageJump(
                pageCount = pageCount,
                currentPage = state.currentPage,
                onCommit = onJumpToPage,
            )

            Spacer(Modifier.height(spacing.space2))

            PanelyTextButton(
                label = if (currentPageBookmarked) {
                    stringResource(R.string.reader_bookmark_remove)
                } else {
                    stringResource(R.string.reader_bookmark_current)
                },
                onClick = onToggleBookmark,
                primary = currentPageBookmarked,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(spacing.space1))

            PanelyTextButton(
                label = stringResource(R.string.reader_settings_more),
                onClick = onOpenSettings,
                primary = false,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * 페이지 점프 슬라이더 + 라벨.
 *
 * Guidelines §6 Slider 규칙:
 * - 드래그 중 라이브 프리뷰 금지 — 손을 떼야 적용 (디코드 폭주/잔상 방지)
 * - 라벨은 드래그 중 점프 대상을 미리 보여줌(숫자 표시이므로 허용)
 */
@Composable
private fun PageJump(
    pageCount: Int,
    currentPage: Int,
    onCommit: (Int) -> Unit,
) {
    val typography = LocalPanelyInkTypography.current
    val density = LocalDensity.current
    val spacing = LocalPanelyInkSpacing.current

    val handleDp = 24.dp
    val handlePx = with(density) { handleDp.toPx() }
    val trackDp = 4.dp
    val totalDp = 48.dp

    var widthPx by remember { mutableStateOf(0) }
    var dragX by remember(currentPage, pageCount) { mutableStateOf<Float?>(null) }
    var inputText by remember(currentPage, pageCount) { mutableStateOf((currentPage + 1).toString()) }

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

    fun commitInput() {
        val page = inputText.toIntOrNull()?.coerceIn(1, pageCount) ?: return
        val index = page - 1
        if (index != currentPage) onCommit(index)
        inputText = page.toString()
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(spacing.space2),
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
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
            Box(
                modifier = Modifier
                    .width(84.dp)
                    .height(48.dp)
                    .background(PanelyInkColors.Paper)
                    .border(2.dp, PanelyInkColors.Ink)
                    .padding(horizontal = spacing.space1),
                contentAlignment = Alignment.Center,
            ) {
                BasicTextField(
                    value = inputText,
                    onValueChange = { raw ->
                        inputText = raw.filter(Char::isDigit).take(5)
                    },
                    textStyle = typography.body.copy(color = PanelyInkColors.Ink),
                    cursorBrush = SolidColor(PanelyInkColors.Ink),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(onDone = { commitInput() }),
                    modifier = Modifier.sizeIn(minWidth = 1.dp),
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = "${previewPage + 1} / $pageCount",
            style = typography.caption,
            color = PanelyInkColors.Ink,
        )
    }
}
