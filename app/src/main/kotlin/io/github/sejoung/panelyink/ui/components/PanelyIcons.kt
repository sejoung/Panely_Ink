package io.github.sejoung.panelyink.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.sejoung.panelyink.ui.theme.PanelyInkColors

/**
 * Design Guidelines §8 — UI 아이콘은 outline 2dp stroke, 24dp 표준.
 *
 * Material 아이콘 패키지를 끌어오지 않고 Canvas로 직접 그린다 — 의존성 최소화 +
 * stroke 두께를 가이드라인 그대로 보장.
 */
private object IconConstants {
    val DefaultSize: Dp = 24.dp
    val Stroke: Dp = 2.dp
}

@Composable
fun PanelyPlusIcon(
    modifier: Modifier = Modifier,
    tint: Color = PanelyInkColors.Ink,
    size: Dp = IconConstants.DefaultSize,
) {
    Canvas(modifier = modifier.size(size)) {
        val s = this.size
        val cx = s.width / 2f
        val cy = s.height / 2f
        val pad = s.minDimension * 0.15f
        val stroke = Stroke(width = IconConstants.Stroke.toPx())
        drawLine(tint, Offset(pad, cy), Offset(s.width - pad, cy), strokeWidth = stroke.width)
        drawLine(tint, Offset(cx, pad), Offset(cx, s.height - pad), strokeWidth = stroke.width)
    }
}

@Composable
fun PanelyMenuIcon(
    modifier: Modifier = Modifier,
    tint: Color = PanelyInkColors.Ink,
    size: Dp = IconConstants.DefaultSize,
) {
    Canvas(modifier = modifier.size(size)) {
        val s = this.size
        val pad = s.minDimension * 0.15f
        val stroke = IconConstants.Stroke.toPx()
        val xs = listOf(0.25f, 0.5f, 0.75f)
        for (frac in xs) {
            val y = s.height * frac
            drawLine(tint, Offset(pad, y), Offset(s.width - pad, y), strokeWidth = stroke)
        }
    }
}

@Composable
fun PanelyMinusIcon(
    modifier: Modifier = Modifier,
    tint: Color = PanelyInkColors.Ink,
    size: Dp = IconConstants.DefaultSize,
) {
    Canvas(modifier = modifier.size(size)) {
        val s = this.size
        val cy = s.height / 2f
        val pad = s.minDimension * 0.15f
        drawLine(tint, Offset(pad, cy), Offset(s.width - pad, cy), strokeWidth = IconConstants.Stroke.toPx())
    }
}

/** 폴더 아이콘 — 탭 모양 outline. */
@Composable
fun PanelyFolderIcon(
    modifier: Modifier = Modifier,
    tint: Color = PanelyInkColors.Ink,
    size: Dp = IconConstants.DefaultSize,
) {
    Canvas(modifier = modifier.size(size)) {
        val s = this.size
        val stroke = IconConstants.Stroke.toPx()
        val pad = s.minDimension * 0.12f
        // 본체: 좌상단부터 우하단까지 사각형, 위쪽에 탭(돌출부) 표현
        val tabWidth = s.width * 0.4f
        val tabHeight = s.height * 0.12f
        val bodyTop = pad + tabHeight
        val left = pad
        val right = s.width - pad
        val bottom = s.height - pad

        val path = Path().apply {
            moveTo(left, bodyTop)
            lineTo(left, pad)
            lineTo(left + tabWidth, pad)
            lineTo(left + tabWidth + tabHeight, bodyTop)
            lineTo(right, bodyTop)
            lineTo(right, bottom)
            lineTo(left, bottom)
            close()
        }
        drawPath(path = path, color = tint, style = Stroke(width = stroke))
    }
}

/** 책 아이콘 — 세워둔 책 outline. */
@Composable
fun PanelyBookIcon(
    modifier: Modifier = Modifier,
    tint: Color = PanelyInkColors.Ink,
    size: Dp = IconConstants.DefaultSize,
) {
    Canvas(modifier = modifier.size(size)) {
        val s = this.size
        val stroke = IconConstants.Stroke.toPx()
        val padX = s.width * 0.20f
        val padY = s.height * 0.10f
        val left = padX
        val right = s.width - padX
        val top = padY
        val bottom = s.height - padY
        // 책 외곽
        drawRect(
            color = tint,
            topLeft = Offset(left, top),
            size = Size(right - left, bottom - top),
            style = Stroke(width = stroke),
        )
        // 책등 안쪽 라인 (좌측에서 살짝 들어와 세로선)
        val spineX = left + (right - left) * 0.18f
        drawLine(tint, Offset(spineX, top), Offset(spineX, bottom), strokeWidth = stroke)
    }
}

/** 우측을 향한 chevron — breadcrumb 구분자 / 행 끝 인디케이터. */
@Composable
fun PanelyChevronRightIcon(
    modifier: Modifier = Modifier,
    tint: Color = PanelyInkColors.Mute,
    size: Dp = IconConstants.DefaultSize,
) {
    Canvas(modifier = modifier.size(size)) {
        val s = this.size
        val stroke = IconConstants.Stroke.toPx()
        val cx = s.width * 0.40f
        val tip = s.width * 0.65f
        val top = s.height * 0.25f
        val mid = s.height * 0.50f
        val bot = s.height * 0.75f
        drawLine(tint, Offset(cx, top), Offset(tip, mid), strokeWidth = stroke)
        drawLine(tint, Offset(tip, mid), Offset(cx, bot), strokeWidth = stroke)
    }
}

/**
 * 톱니바퀴 — 앱 설정 진입. e-ink 친화 단순 outline: 외곽 톱니 8개 + 안쪽 원.
 *
 * Material 표준 16개 톱니는 디테일이 많아 1648×1236 화면에서 흐릿. 8개로 단순화.
 */
@Composable
fun PanelySettingsIcon(
    modifier: Modifier = Modifier,
    tint: Color = PanelyInkColors.Ink,
    size: Dp = IconConstants.DefaultSize,
) {
    Canvas(modifier = modifier.size(size)) {
        val s = this.size
        val stroke = IconConstants.Stroke.toPx()
        val cx = s.width / 2f
        val cy = s.height / 2f
        val outerR = s.minDimension * 0.40f
        val toothR = s.minDimension * 0.46f
        val innerR = s.minDimension * 0.18f
        // 안쪽 원 (구멍)
        drawCircle(
            color = tint,
            radius = innerR,
            center = Offset(cx, cy),
            style = Stroke(width = stroke),
        )
        // 외곽 원
        drawCircle(
            color = tint,
            radius = outerR,
            center = Offset(cx, cy),
            style = Stroke(width = stroke),
        )
        // 8개 톱니 — 외곽 원 위로 짧은 막대
        val toothCount = 8
        for (i in 0 until toothCount) {
            val angle = (Math.PI * 2 * i / toothCount).toFloat()
            val sx = cx + outerR * kotlin.math.cos(angle)
            val sy = cy + outerR * kotlin.math.sin(angle)
            val ex = cx + toothR * kotlin.math.cos(angle)
            val ey = cy + toothR * kotlin.math.sin(angle)
            drawLine(tint, Offset(sx, sy), Offset(ex, ey), strokeWidth = stroke)
        }
    }
}

/** 돋보기 — 라이브러리 헤더 검색 진입. */
@Composable
fun PanelySearchIcon(
    modifier: Modifier = Modifier,
    tint: Color = PanelyInkColors.Ink,
    size: Dp = IconConstants.DefaultSize,
) {
    Canvas(modifier = modifier.size(size)) {
        val s = this.size
        val stroke = IconConstants.Stroke.toPx()
        val r = s.minDimension * 0.26f
        val cx = s.width * 0.42f
        val cy = s.height * 0.42f
        // 원
        drawCircle(
            color = tint,
            radius = r,
            center = Offset(cx, cy),
            style = Stroke(width = stroke),
        )
        // 손잡이 — 원 우하단부터 캔버스 우하단 방향
        val handleStart = Offset(cx + r * 0.7f, cy + r * 0.7f)
        val handleEnd = Offset(s.width - s.width * 0.16f, s.height - s.height * 0.16f)
        drawLine(tint, handleStart, handleEnd, strokeWidth = stroke)
    }
}

/** X — 닫기/취소 액션. */
@Composable
fun PanelyCloseIcon(
    modifier: Modifier = Modifier,
    tint: Color = PanelyInkColors.Ink,
    size: Dp = IconConstants.DefaultSize,
) {
    Canvas(modifier = modifier.size(size)) {
        val s = this.size
        val stroke = IconConstants.Stroke.toPx()
        val pad = s.minDimension * 0.22f
        drawLine(
            tint,
            Offset(pad, pad),
            Offset(s.width - pad, s.height - pad),
            strokeWidth = stroke,
        )
        drawLine(
            tint,
            Offset(s.width - pad, pad),
            Offset(pad, s.height - pad),
            strokeWidth = stroke,
        )
    }
}

/**
 * 정렬 아이콘 — 라인 두 줄(긴/짧)이 위/아래로 쌓인 outline.
 * 위쪽 라인이 길어 "내림차순" 시각적 의미. 정렬 모드와 무관하게 같은 아이콘 사용.
 */
@Composable
fun PanelySortIcon(
    modifier: Modifier = Modifier,
    tint: Color = PanelyInkColors.Ink,
    size: Dp = IconConstants.DefaultSize,
) {
    Canvas(modifier = modifier.size(size)) {
        val s = this.size
        val stroke = IconConstants.Stroke.toPx()
        val padX = s.width * 0.18f
        val left = padX
        val right = s.width - padX
        val rows = 4
        val padTop = s.height * 0.20f
        val padBottom = s.height * 0.20f
        val span = s.height - padTop - padBottom
        // 위에서 아래로 점점 짧아지는 4줄
        for (i in 0 until rows) {
            val y = padTop + (span * i / (rows - 1))
            val widthFactor = 1f - (i * 0.2f) // 1.0, 0.8, 0.6, 0.4
            val x2 = left + (right - left) * widthFactor
            drawLine(tint, Offset(left, y), Offset(x2, y), strokeWidth = stroke)
        }
    }
}

/** 좌향 화살표 — 헤더 back 액션. chevron보다 머리가 작고 본체에 라인이 있다. */
@Composable
fun PanelyArrowBackIcon(
    modifier: Modifier = Modifier,
    tint: Color = PanelyInkColors.Ink,
    size: Dp = IconConstants.DefaultSize,
) {
    Canvas(modifier = modifier.size(size)) {
        val s = this.size
        val stroke = IconConstants.Stroke.toPx()
        val padX = s.width * 0.18f
        val cy = s.height / 2f
        val tip = padX
        val tail = s.width - padX
        val head = s.width * 0.36f
        val headSpan = s.height * 0.25f
        // 본체
        drawLine(tint, Offset(tip, cy), Offset(tail, cy), strokeWidth = stroke)
        // 머리 두 변
        drawLine(tint, Offset(tip, cy), Offset(head, cy - headSpan), strokeWidth = stroke)
        drawLine(tint, Offset(tip, cy), Offset(head, cy + headSpan), strokeWidth = stroke)
    }
}

/** 우향 화살표 — 다음 권 / forward 액션. */
@Composable
fun PanelyArrowForwardIcon(
    modifier: Modifier = Modifier,
    tint: Color = PanelyInkColors.Ink,
    size: Dp = IconConstants.DefaultSize,
) {
    Canvas(modifier = modifier.size(size)) {
        val s = this.size
        val stroke = IconConstants.Stroke.toPx()
        val padX = s.width * 0.18f
        val cy = s.height / 2f
        val tip = s.width - padX
        val tail = padX
        val head = s.width * 0.64f
        val headSpan = s.height * 0.25f
        drawLine(tint, Offset(tail, cy), Offset(tip, cy), strokeWidth = stroke)
        drawLine(tint, Offset(tip, cy), Offset(head, cy - headSpan), strokeWidth = stroke)
        drawLine(tint, Offset(tip, cy), Offset(head, cy + headSpan), strokeWidth = stroke)
    }
}

/** 북마크 — 현재 페이지 저장/해제 액션. */
@Composable
fun PanelyBookmarkIcon(
    modifier: Modifier = Modifier,
    tint: Color = PanelyInkColors.Ink,
    size: Dp = IconConstants.DefaultSize,
) {
    Canvas(modifier = modifier.size(size)) {
        val s = this.size
        val stroke = IconConstants.Stroke.toPx()
        val left = s.width * 0.28f
        val right = s.width * 0.72f
        val top = s.height * 0.16f
        val bottom = s.height * 0.84f
        val notchY = s.height * 0.64f
        val path = Path().apply {
            moveTo(left, top)
            lineTo(right, top)
            lineTo(right, bottom)
            lineTo(s.width * 0.50f, notchY)
            lineTo(left, bottom)
            close()
        }
        drawPath(path = path, color = tint, style = Stroke(width = stroke))
    }
}
