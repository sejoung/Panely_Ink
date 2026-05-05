package io.github.sejoung.panelyink.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Design Guidelines §3. 8dp grid.
 *
 * 본문 리더 모드는 이 값을 사용하지 않는다 — fit 계산 결과로 직접 렌더된다.
 */
@Immutable
data class PanelyInkSpacing(
    val space1: Dp = 8.dp,
    val space2: Dp = 16.dp,
    val space3: Dp = 24.dp,
    val space4: Dp = 32.dp,
    val space5: Dp = 48.dp,
)

val LocalPanelyInkSpacing = staticCompositionLocalOf { PanelyInkSpacing() }
