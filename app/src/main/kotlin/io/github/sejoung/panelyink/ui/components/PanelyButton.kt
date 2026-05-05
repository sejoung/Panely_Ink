package io.github.sejoung.panelyink.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.sejoung.panelyink.ui.theme.LocalPanelyInkTypography
import io.github.sejoung.panelyink.ui.theme.PanelyInkColors

/**
 * Design Guidelines §6 + §7.
 *
 * Pressed/Selected는 fill 반전으로 표현. 색이 아니라 형상 변화로 상태를 알린다.
 * elevation·ripple·shadow·radius 일체 없음 (Guidelines는 radius ≤2dp 허용이지만 M0은 0).
 */
@Composable
fun PanelyButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = true,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    val typography = LocalPanelyInkTypography.current
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()

    val invert = primary || pressed
    val bg = when {
        !enabled -> PanelyInkColors.Paper
        invert -> PanelyInkColors.Ink
        else -> PanelyInkColors.Paper
    }
    val fg = when {
        !enabled -> PanelyInkColors.Mute
        invert -> PanelyInkColors.Paper
        else -> PanelyInkColors.Ink
    }
    val border = if (!enabled) {
        BorderStroke(2.dp, PanelyInkColors.Mute)
    } else {
        BorderStroke(2.dp, PanelyInkColors.Ink)
    }

    Box(
        modifier = modifier
            .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
            .background(bg)
            .border(border)
            .clickable(
                enabled = enabled,
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        CompositionLocalProvider(
            LocalContentColor provides fg,
            LocalTextStyle provides typography.body.copy(color = fg),
        ) { content() }
    }
}

@Composable
fun PanelyTextButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    enabled: Boolean = true,
) {
    PanelyButton(
        onClick = onClick,
        modifier = modifier.sizeIn(minHeight = 48.dp),
        primary = primary,
        enabled = enabled,
    ) {
        Text(label)
    }
}
