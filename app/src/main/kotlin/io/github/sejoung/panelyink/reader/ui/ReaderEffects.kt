package io.github.sejoung.panelyink.reader.ui

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import io.github.sejoung.panelyink.core.position.PositionRepository
import io.github.sejoung.panelyink.core.preferences.AppPreferencesRepository
import io.github.sejoung.panelyink.data.db.settings.BookSettingsRepository
import io.github.sejoung.panelyink.library.model.BookEntry
import io.github.sejoung.panelyink.reader.ReaderState
import io.github.sejoung.panelyink.reader.ReaderViewModel
import io.github.sejoung.panelyink.reader.input.ReaderInput
import io.github.sejoung.panelyink.reader.model.BookSettings
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
    view,
  ) {
    view?.setPageIndex(state.currentPage)
    view?.setFitMode(state.fitMode)
    view?.setTrimEnabled(state.trimEnabled)
    view?.setContrast(state.contrast)
    view?.setInvertEnabled(state.invertEnabled)
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

  LaunchedEffect(viewModel) {
    try {
      viewModel.state
        .map { it.toBookSettings() }
        .drop(1)
        .distinctUntilChanged()
        .debounce(SETTINGS_SAVE_DEBOUNCE_MS)
        .collect { settings ->
          bookSettingsRepo.save(
            bookId = viewModel.bookId,
            settings = settings,
          )
        }
    } finally {
      withContext(NonCancellable) {
        bookSettingsRepo.save(
          bookId = viewModel.bookId,
          settings = viewModel.state.value.toBookSettings(),
        )
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
  onNavigate: (BookEntry, BookSettings) -> Unit,
) {
  val activity = LocalContext.current.findMainActivity()
  DisposableEffect(
    activity,
    viewModel,
    menuOpen,
    settingsOpen,
    bookmarksOpen,
    state.currentPage,
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
              onNavigate(previousBook, state.toBookSettings())
            } else {
              viewModel.goPrevious()
            }
          },
          onNext = {
            val nextBook = nextBookForBoundary(state, viewModel.pageCount, context)
            if (nextBook != null) {
              onNavigate(nextBook, state.toBookSettings())
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
