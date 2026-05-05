package io.github.sejoung.panelyink.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Design Guidelines §2.
 * Carta 1200 표시는 16-grayscale이지만 UI에서는 4토큰만 사용한다.
 * Ink/Paper 외 두 가지 회색을 한 화면에 동시에 두지 않는다.
 */
object PanelyInkColors {
    val Ink: Color = Color(0xFF111111)
    val Paper: Color = Color(0xFFFFFFFF)
    val Mute: Color = Color(0xFF6B6B6B)
    val Hairline: Color = Color(0xFFC8C8C8)
}
