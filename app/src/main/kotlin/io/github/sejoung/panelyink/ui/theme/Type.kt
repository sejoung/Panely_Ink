package io.github.sejoung.panelyink.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.sp

/**
 * Design Guidelines §4.
 *
 * - 시스템 폰트만 사용 (커스텀 폰트는 디코드 비용 + 잔상 위험)
 * - Light/Thin 굵기 금지 (Carta에서 끊어 보임)
 * - 줄 간격 1.4em 고정
 * - Compose에서 한·영 라인박스 일관성을 위해 LineHeightStyle.Trim.None
 */
@Immutable
data class PanelyInkTypography(
    val display: TextStyle,
    val title: TextStyle,
    val body: TextStyle,
    val list: TextStyle,
    val caption: TextStyle,
    val mono: TextStyle,
)

private val lineHeightTrim = LineHeightStyle(
    alignment = LineHeightStyle.Alignment.Center,
    trim = LineHeightStyle.Trim.None,
)
private val platform = PlatformTextStyle(includeFontPadding = false)

private fun base(
    sizeSp: Int,
    weight: FontWeight,
    family: FontFamily = FontFamily.Default,
): TextStyle = TextStyle(
    fontFamily = family,
    fontWeight = weight,
    fontSize = sizeSp.sp,
    lineHeight = (sizeSp * 1.4f).sp,
    lineHeightStyle = lineHeightTrim,
    platformStyle = platform,
)

val PanelyInkDefaultTypography = PanelyInkTypography(
    display = base(28, FontWeight.Medium),
    title = base(22, FontWeight.Medium),
    body = base(16, FontWeight.Normal),
    list = base(18, FontWeight.Normal),
    caption = base(14, FontWeight.Normal),
    mono = base(14, FontWeight.Normal, FontFamily.Monospace),
)

val LocalPanelyInkTypography = staticCompositionLocalOf { PanelyInkDefaultTypography }

/**
 * Material3 components가 직접 참조하는 Typography로의 매핑.
 * 우리 6개 토큰 → Material3 슬롯 중 가장 의미가 가까운 것.
 */
internal fun PanelyInkTypography.toMaterial3(): Typography = Typography(
    displayMedium = display,
    titleLarge = title,
    bodyLarge = body,
    bodyMedium = list,
    labelMedium = caption,
    labelSmall = mono,
)
