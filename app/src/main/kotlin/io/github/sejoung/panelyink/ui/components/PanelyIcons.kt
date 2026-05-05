package io.github.sejoung.panelyink.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
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
