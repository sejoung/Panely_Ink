package io.github.sejoung.panelyink.reader

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
import io.github.sejoung.panelyink.data.db.BookSettingsRepository
import io.github.sejoung.panelyink.library.BookEntry

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
    onCloseMenu: () -> Unit,
) {
    BackHandler(enabled = menuOpen && !settingsOpen) {
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
internal fun ReaderPersistenceEffects(
    state: ReaderState,
    viewModel: ReaderViewModel,
    positionRepo: PositionRepository,
    bookSettingsRepo: BookSettingsRepository,
    appPrefsRepo: AppPreferencesRepository,
) {
    LaunchedEffect(state.currentPage, viewModel) {
        positionRepo.save(
            bookId = viewModel.bookId,
            pageIndex = state.currentPage,
            pageCount = viewModel.pageCount,
        )
    }

    LaunchedEffect(
        viewModel,
        state.fitMode,
        state.direction,
        state.trimEnabled,
        state.contrast,
    ) {
        bookSettingsRepo.save(
            bookId = viewModel.bookId,
            settings = state.toBookSettings(),
        )
    }

    LaunchedEffect(viewModel, state.invertEnabled) {
        appPrefsRepo.setInvertEnabled(state.invertEnabled)
    }
    LaunchedEffect(viewModel, state.fullRefreshInterval) {
        appPrefsRepo.setFullRefreshInterval(state.fullRefreshInterval)
    }
}

@Composable
internal fun ReaderHardwareKeyHandler(
    state: ReaderState,
    viewModel: ReaderViewModel,
    context: SeriesContext,
    menuOpen: Boolean,
    settingsOpen: Boolean,
    onCloseMenu: () -> Unit,
    onCloseSettings: () -> Unit,
    onNavigate: (BookEntry, BookSettings) -> Unit,
) {
    val activity = LocalContext.current.findMainActivity()
    DisposableEffect(
        activity,
        viewModel,
        menuOpen,
        settingsOpen,
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
