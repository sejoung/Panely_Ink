package io.github.sejoung.panelyink.reader.ui

import android.content.pm.ActivityInfo
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import io.github.sejoung.panelyink.core.position.PositionRepository
import io.github.sejoung.panelyink.core.preferences.AppPreferencesRepository
import io.github.sejoung.panelyink.core.preferences.ReaderOrientation
import io.github.sejoung.panelyink.data.db.settings.BookSettingsRepository
import io.github.sejoung.panelyink.core.book.BookRef
import io.github.sejoung.panelyink.reader.ReaderState
import io.github.sejoung.panelyink.reader.ReaderViewModel
import io.github.sejoung.panelyink.reader.input.ReaderInput
import io.github.sejoung.panelyink.reader.model.BookSettingsOverrides
import io.github.sejoung.panelyink.reader.model.SeriesContext
import io.github.sejoung.panelyink.reader.session.CbzBookSession
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

@Composable
internal fun ReaderSystemBarsEffect() {
  val view = LocalView.current
  val activity = LocalContext.current.findMainActivity()
  DisposableEffect(activity, view) {
    val window = activity?.window
    if (window != null) {
      val controller = WindowCompat.getInsetsController(window, view)
      controller.systemBarsBehavior =
        WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
      controller.hide(WindowInsetsCompat.Type.systemBars())
    }
    onDispose {
      if (window != null) {
        WindowCompat.getInsetsController(window, view)
          .show(WindowInsetsCompat.Type.systemBars())
      }
    }
  }
}

/**
 * 책별 회전 잠금을 Activity에 반영. 본문 화면 라이프사이클 동안만 적용되고, 화면을 떠나면
 * 진입 직전 값(`UNSPECIFIED`)으로 복원해 라이브러리/설정 화면이 시스템 회전을 따르도록 유지.
 *
 * AndroidManifest에 `configChanges="orientation|screenSize|..."`이 있어 회전 시 Activity는 재생성되지
 * 않는다 — ReaderViewModel/세션이 그대로 유지되고, ReaderView는 `onSizeChanged`로 새 viewport를 받는다.
 *
 * **strict PORTRAIT/LANDSCAPE 사용 이유 (v1.1 변경):**
 * Meebook M7 같은 e-ink 단말은 가속도계가 빈약하거나 시스템 자동회전이 기본 꺼짐이라
 * `SCREEN_ORIENTATION_SENSOR_*` 변종이 무시되는 경우가 흔하다. 명시적 잠금은 시스템
 * 자동회전 설정과 무관하게 적용되므로 strict 변종이 안전. 사용자가 단말을 180° 뒤집어
 * 잡을 수는 없게 되지만, 한 번 잡은 방향 그대로 쓰는 책 읽기 패턴에는 영향 없음.
 */
@Composable
internal fun ReaderOrientationEffect(orientation: ReaderOrientation) {
  val activity = LocalContext.current.findMainActivity()
  // 화면 진입 직전 값을 한 번 캡처. orientation이 바뀌어도 이 값은 그대로 — 떠날 때 원복용.
  val originalOrientation = remember(activity) {
    activity?.requestedOrientation ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
  }
  // orientation이 바뀔 때마다 즉시 적용. dispose 없는 effect라 중간 플리커 없음.
  LaunchedEffect(activity, orientation) {
    activity?.requestedOrientation = when (orientation) {
      ReaderOrientation.Portrait -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
      ReaderOrientation.Landscape -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
    }
  }
  // ReaderScreen을 떠날 때만 원복 — 라이브러리/설정은 시스템 회전을 따르게.
  DisposableEffect(activity) {
    onDispose {
      activity?.requestedOrientation = originalOrientation
    }
  }
}

@Composable
internal fun ReaderLifecycleEffect(
  session: CbzBookSession,
  viewModel: ReaderViewModel,
) {
  DisposableEffect(viewModel, session) {
    onDispose {
      viewModel.close()
      session.close()
    }
  }
}

@Composable
internal fun ReaderBackHandler(
  menuOpen: Boolean,
  settingsOpen: Boolean,
  bookmarksOpen: Boolean,
  onCloseMenu: () -> Unit,
  onCloseBookmarks: () -> Unit,
) {
  BackHandler(enabled = bookmarksOpen) {
    onCloseBookmarks()
    onCloseMenu()
  }
  BackHandler(enabled = menuOpen && !settingsOpen && !bookmarksOpen) {
    onCloseMenu()
  }
}

@Composable
internal fun ReaderViewEffects(
  state: ReaderState,
  viewModel: ReaderViewModel,
  view: ReaderView?,
) {
  LaunchedEffect(
    state.currentPage,
    state.fitMode,
    state.trimEnabled,
    state.contrast,
    state.invertEnabled,
    state.spreadMode,
    state.coverAlone,
    state.direction,
    view,
  ) {
    view?.setPageIndex(state.currentPage)
    view?.setFitMode(state.fitMode)
    view?.setTrimEnabled(state.trimEnabled)
    view?.setContrast(state.contrast)
    view?.setInvertEnabled(state.invertEnabled)
    view?.setSpreadMode(state.spreadMode)
    view?.setCoverAlone(state.coverAlone)
    view?.setDirection(state.direction)
  }

  LaunchedEffect(viewModel, view) {
    viewModel.decoded.collect { view?.invalidate() }
  }

  LaunchedEffect(state.fullRefreshGeneration, view) {
    if (state.fullRefreshGeneration > 0) {
      view?.requestFullRefresh()
    }
  }
}

@Composable
@OptIn(FlowPreview::class)
internal fun ReaderPersistenceEffects(
  viewModel: ReaderViewModel,
  positionRepo: PositionRepository,
  bookSettingsRepo: BookSettingsRepository,
  appPrefsRepo: AppPreferencesRepository,
) {
  LaunchedEffect(viewModel) {
    try {
      viewModel.state
        .map { it.currentPage }
        .drop(1)
        .distinctUntilChanged()
        .debounce(POSITION_SAVE_DEBOUNCE_MS)
        .collect { page ->
          positionRepo.save(
            bookId = viewModel.bookId,
            pageIndex = page,
            pageCount = viewModel.pageCount,
          )
        }
    } finally {
      withContext(NonCancellable) {
        val latest = viewModel.state.value.currentPage
        positionRepo.save(
          bookId = viewModel.bookId,
          pageIndex = latest,
          pageCount = viewModel.pageCount,
        )
      }
    }
  }

  // sparse override 모델 — viewModel.overrides는 "사용자가 명시적으로 바꾼 필드"만 담는다.
  // 빈 override이면 repo.save가 row를 삭제 → 다음 진입에 전역값 그대로 적용.
  // 진입 시점 overrides와 비교해 변경이 있을 때만 저장 — 단순히 책을 열어 보기만 한 경우 DB 무변경.
  LaunchedEffect(viewModel) {
    val initialOverrides = viewModel.overrides.value
    try {
      viewModel.overrides
        .drop(1)
        .distinctUntilChanged()
        .debounce(SETTINGS_SAVE_DEBOUNCE_MS)
        .collect { overrides ->
          bookSettingsRepo.save(
            bookId = viewModel.bookId,
            overrides = overrides,
          )
        }
    } finally {
      val latest = viewModel.overrides.value
      if (latest != initialOverrides) {
        withContext(NonCancellable) {
          bookSettingsRepo.save(
            bookId = viewModel.bookId,
            overrides = latest,
          )
        }
      }
    }
  }

  LaunchedEffect(viewModel) {
    try {
      viewModel.state
        .map { it.invertEnabled }
        .drop(1)
        .distinctUntilChanged()
        .debounce(SETTINGS_SAVE_DEBOUNCE_MS)
        .collect { enabled ->
          appPrefsRepo.setInvertEnabled(enabled)
        }
    } finally {
      withContext(NonCancellable) {
        val enabled = viewModel.state.value.invertEnabled
        appPrefsRepo.setInvertEnabled(enabled)
      }
    }
  }

  LaunchedEffect(viewModel) {
    try {
      viewModel.state
        .map { it.fullRefreshInterval }
        .drop(1)
        .distinctUntilChanged()
        .debounce(SETTINGS_SAVE_DEBOUNCE_MS)
        .collect { interval ->
          appPrefsRepo.setFullRefreshInterval(interval)
        }
    } finally {
      withContext(NonCancellable) {
        val interval = viewModel.state.value.fullRefreshInterval
        appPrefsRepo.setFullRefreshInterval(interval)
      }
    }
  }
}

private const val POSITION_SAVE_DEBOUNCE_MS = 400L
private const val SETTINGS_SAVE_DEBOUNCE_MS = 250L

@Composable
internal fun ReaderHardwareKeyHandler(
  state: ReaderState,
  viewModel: ReaderViewModel,
  context: SeriesContext,
  menuOpen: Boolean,
  settingsOpen: Boolean,
  bookmarksOpen: Boolean,
  onCloseMenu: () -> Unit,
  onCloseSettings: () -> Unit,
  onCloseBookmarks: () -> Unit,
  onNavigate: (BookRef, BookSettingsOverrides) -> Unit,
) {
  val activity = LocalContext.current.findMainActivity()
  // dispatcher는 boundary(첫/마지막 페이지)에서만 형제 권 점프 분기를 새로 잡으면 된다.
  // currentPage 자체를 key로 두면 매 페이지 전환마다 클로저를 재할당해 GC 압박이 누적된다.
  // overrides는 형제 권으로 propagate되므로 변경 시에는 dispatcher도 재구성.
  val atFirstPage = state.currentPage == 0
  val atLastPage = state.currentPage == viewModel.pageCount - 1
  val propagatedOverrides by viewModel.overrides.collectAsState()
  DisposableEffect(
    activity,
    viewModel,
    menuOpen,
    settingsOpen,
    bookmarksOpen,
    atFirstPage,
    atLastPage,
    propagatedOverrides,
    context.previousBook,
    context.nextBook,
  ) {
    activity?.keyDispatcher = { event ->
      when {
        settingsOpen -> ReaderInput.dispatch(
          event = event,
          onPrev = onCloseSettings,
          onNext = onCloseSettings,
        )

        bookmarksOpen -> ReaderInput.dispatch(
          event = event,
          onPrev = {
            onCloseBookmarks()
            onCloseMenu()
          },
          onNext = {
            onCloseBookmarks()
            onCloseMenu()
          },
        )

        menuOpen -> ReaderInput.dispatch(
          event = event,
          onPrev = onCloseMenu,
          onNext = onCloseMenu,
        )

        else -> ReaderInput.dispatch(
          event = event,
          onPrev = {
            val previousBook = previousBookForBoundary(state, context)
            if (previousBook != null) {
              onNavigate(previousBook, propagatedOverrides)
            } else {
              viewModel.goPrevious()
            }
          },
          onNext = {
            val nextBook = nextBookForBoundary(state, viewModel.pageCount, context)
            if (nextBook != null) {
              onNavigate(nextBook, propagatedOverrides)
            } else {
              viewModel.goNext()
            }
          },
        )
      }
    }
    onDispose { activity?.keyDispatcher = null }
  }
}
