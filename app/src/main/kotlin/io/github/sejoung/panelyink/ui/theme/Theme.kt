package io.github.sejoung.panelyink.ui.theme

import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.node.DelegatableNode

/**
 * Design Guidelines §1·§2·§4 단일 진입점.
 *
 * - colorScheme: Ink/Paper 양극과 Mute/Hairline만 노출되도록 Material3 슬롯을 매핑
 * - typography: PanelyInkDefaultTypography 6토큰 → Material3 슬롯
 * - LocalIndication / LocalRippleConfiguration: ripple 전면 비활성 (잔상 회피)
 *
 * 본문 뷰어 핫패스는 Compose가 아닌 View+Canvas로 분리될 예정 (PRD §8).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PanelyInkTheme(content: @Composable () -> Unit) {
    val colors = lightColorScheme(
        primary = PanelyInkColors.Ink,
        onPrimary = PanelyInkColors.Paper,
        secondary = PanelyInkColors.Ink,
        onSecondary = PanelyInkColors.Paper,
        tertiary = PanelyInkColors.Ink,
        onTertiary = PanelyInkColors.Paper,
        background = PanelyInkColors.Paper,
        onBackground = PanelyInkColors.Ink,
        surface = PanelyInkColors.Paper,
        onSurface = PanelyInkColors.Ink,
        surfaceVariant = PanelyInkColors.Paper,
        onSurfaceVariant = PanelyInkColors.Mute,
        outline = PanelyInkColors.Hairline,
        outlineVariant = PanelyInkColors.Hairline,
        error = PanelyInkColors.Ink,
        onError = PanelyInkColors.Paper,
    )
    val typography = PanelyInkDefaultTypography

    CompositionLocalProvider(
        LocalPanelyInkTypography provides typography,
        LocalPanelyInkSpacing provides PanelyInkSpacing(),
        LocalIndication provides NoIndication,
        LocalRippleConfiguration provides null,
    ) {
        MaterialTheme(
            colorScheme = colors,
            typography = typography.toMaterial3(),
            content = content,
        )
    }
}

/**
 * 비-Composable 컨텍스트(예: Activity onCreate background)에서 토큰을
 * 즉시 참조하기 위한 정적 facade. CompositionLocal이 가능한 곳에서는
 * MaterialTheme.colorScheme 또는 LocalPanelyInk* 를 우선 사용한다.
 */
object PanelyInkTokens {
    val Color = PanelyInkColors
    val Spacing = PanelyInkSpacing()
    val Typography = PanelyInkDefaultTypography
}

private object NoIndication : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode =
        object : Modifier.Node() {}
    override fun equals(other: Any?): Boolean = other === this
    override fun hashCode(): Int = -1
}
