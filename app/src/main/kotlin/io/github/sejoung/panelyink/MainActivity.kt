package io.github.sejoung.panelyink

import android.annotation.SuppressLint
import android.content.Context
import android.os.Bundle
import android.view.KeyEvent
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
import io.github.sejoung.panelyink.core.preferences.AppLocale
import io.github.sejoung.panelyink.library.ui.LibraryScreen
import io.github.sejoung.panelyink.reader.model.BookSettingsOverrides
import io.github.sejoung.panelyink.reader.model.SeriesContext
import io.github.sejoung.panelyink.reader.ui.ReaderScreen
import io.github.sejoung.panelyink.ui.theme.PanelyInkTheme
import io.github.sejoung.panelyink.ui.theme.PanelyInkTokens

/**
 * 단일 Activity 호스트. 화면 두 개(Library/Reader)라 navigation-compose 의존을
 * 끌어오지 않고 sealed 분기로 처리. 화면이 늘어나면 (M3 시리즈 카드, 메뉴 등)
 * 정식 navigation 라이브러리로 교체.
 *
 * [keyDispatcher]: 본문 화면이 포커스일 때 하드웨어 키(볼륨, Page Up/Down 등)를
 * 가로채기 위한 콜백. ReaderScreen이 mount 시 set, dispose 시 unset.
 * Activity의 `dispatchKeyEvent` 단계에서 consume → 시스템 볼륨 변동 같은 부작용 차단.
 */
class MainActivity : ComponentActivity() {

  var keyDispatcher: ((KeyEvent) -> Boolean)? = null

  override fun attachBaseContext(newBase: Context) {
    super.attachBaseContext(AppLocale.wrapFromPreferences(newBase))
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContent {
      PanelyInkTheme {
        var screen by remember { mutableStateOf<Screen>(Screen.Library) }
        Box(Modifier
          .fillMaxSize()
          .background(PanelyInkTokens.Color.Paper)) {
          when (val s = screen) {
            Screen.Library -> LibraryScreen(
              onOpenBook = { ctx, initialPage ->
                screen = Screen.Reader(ctx, initialPageOverride = initialPage)
              },
            )

            is Screen.Reader -> ReaderScreen(
              context = s.context,
              propagatedOverrides = s.propagatedOverrides,
              initialPageOverride = s.initialPageOverride,
              onBack = { screen = Screen.Library },
              // 시리즈 형제 권으로 이동 — siblings는 그대로 두고 current만 교체.
              // 새 ReaderScreen이 produceState 키(current.documentUri)로 재진입.
              // sparse overrides만 전달 — 미설정 필드는 형제 권의 전역 기본값을 따른다.
              onNavigate = { newCurrent, overrides ->
                screen = Screen.Reader(
                  context = s.context.copy(current = newCurrent),
                  propagatedOverrides = overrides,
                  initialPageOverride = null,
                )
              },
            )
          }
        }
      }
    }
  }

  // ComponentActivity.dispatchKeyEvent는 androidx.core에서 @RestrictedApi로 선언돼 있어
  // override 시 lint가 RestrictedApi 에러를 낸다. 의도된 override이므로 명시적 suppress.
  @SuppressLint("RestrictedApi")
  override fun dispatchKeyEvent(event: KeyEvent): Boolean {
    if (keyDispatcher?.invoke(event) == true) return true
    return super.dispatchKeyEvent(event)
  }

  private sealed interface Screen {
    data object Library : Screen
    data class Reader(
      val context: SeriesContext,
      val propagatedOverrides: BookSettingsOverrides? = null,
      val initialPageOverride: Int? = null,
    ) : Screen
  }
}
