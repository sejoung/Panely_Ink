package io.github.sejoung.panelyink

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import io.github.sejoung.panelyink.library.LibraryScreen
import io.github.sejoung.panelyink.ui.theme.PanelyInkTheme
import io.github.sejoung.panelyink.ui.theme.PanelyInkTokens

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            PanelyInkTheme {
                Box(Modifier.fillMaxSize().background(PanelyInkTokens.Color.Paper)) {
                    LibraryScreen()
                }
            }
        }
    }
}
