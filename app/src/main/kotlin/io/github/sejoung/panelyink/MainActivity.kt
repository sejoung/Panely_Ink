package io.github.sejoung.panelyink

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import io.github.sejoung.panelyink.library.LibraryEntry
import io.github.sejoung.panelyink.library.LibraryScreen
import io.github.sejoung.panelyink.reader.ReaderScreen
import io.github.sejoung.panelyink.ui.theme.PanelyInkTheme
import io.github.sejoung.panelyink.ui.theme.PanelyInkTokens

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            PanelyInkTheme {
                var screen by remember { mutableStateOf<Screen>(Screen.Library) }
                Box(Modifier.fillMaxSize().background(PanelyInkTokens.Color.Paper)) {
                    when (val s = screen) {
                        Screen.Library -> LibraryScreen(
                            onOpenBook = { entry -> screen = Screen.Reader(entry) },
                        )
                        is Screen.Reader -> ReaderScreen(
                            entry = s.entry,
                            onBack = { screen = Screen.Library },
                        )
                    }
                }
            }
        }
    }

    /**
     * 화면 2개라 navigation-compose 의존을 끌어오지 않고 sealed 분기로 충분.
     * 화면이 늘어나면 (M3 시리즈 카드, 메뉴 등) navigation-compose 또는 nav3로 교체.
     */
    private sealed interface Screen {
        data object Library : Screen
        data class Reader(val entry: LibraryEntry) : Screen
    }
}
