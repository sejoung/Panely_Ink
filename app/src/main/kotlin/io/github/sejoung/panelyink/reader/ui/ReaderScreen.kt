package io.github.sejoung.panelyink.reader.ui

import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.sejoung.panelyink.R
import io.github.sejoung.panelyink.core.book.BookRef
import io.github.sejoung.panelyink.core.position.PositionRepository
import io.github.sejoung.panelyink.core.preferences.AppPreferences
import io.github.sejoung.panelyink.core.preferences.AppPreferencesRepository
import io.github.sejoung.panelyink.data.db.PanelyDatabase
import io.github.sejoung.panelyink.data.db.bookmark.BookmarkRepository
import io.github.sejoung.panelyink.data.db.bookmark.RoomBookmarkRepository
import io.github.sejoung.panelyink.data.db.position.RoomPositionRepository
import io.github.sejoung.panelyink.data.db.settings.BookSettingsRepository
import io.github.sejoung.panelyink.data.db.settings.RoomBookSettingsRepository
import io.github.sejoung.panelyink.data.preferences.SharedPrefsAppPreferencesRepository
import io.github.sejoung.panelyink.reader.ReaderViewModel
import io.github.sejoung.panelyink.reader.input.ReaderInput
import io.github.sejoung.panelyink.reader.model.BookSettings
import io.github.sejoung.panelyink.reader.model.BookSettingsOverrides
import io.github.sejoung.panelyink.reader.model.SeriesContext
import io.github.sejoung.panelyink.reader.model.resolve
import io.github.sejoung.panelyink.reader.session.CbzBookSession
import io.github.sejoung.panelyink.ui.theme.LocalPanelyInkSpacing
import io.github.sejoung.panelyink.ui.theme.LocalPanelyInkTypography
import io.github.sejoung.panelyink.ui.theme.PanelyInkColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * 본문 리더 진입 화면.
 *
 * v1.0 M1.2 스코프:
 * - 책을 열어 페이지를 표시
 * - 3분할 탭 영역 (좌 30 / 중앙 40 / 우 30) + LTR/RTL 자동 반전 — Guidelines §5
 * - 하드웨어 키(볼륨/Page Up·Down/DPad) — [ReaderInput]
 *
 * 메뉴(중앙 탭)와 fit 변경 UI는 M1.3에서 채움.
 */
@Composable
fun ReaderScreen(
  context: SeriesContext,
  propagatedOverrides: BookSettingsOverrides? = null,
  initialPageOverride: Int? = null,
  onBack: () -> Unit,
  /**
   * 시리즈 형제 권으로 이동 — 호스트가 새 [SeriesContext]를 만들어 [ReaderScreen]을 다시
   * 부르는 식으로 처리. M4 시리즈 연속 읽기 카드 / "다음 권" 카드에서 사용.
   * 인자로 받은 [BookRef]는 [SeriesContext.siblings] 안에 있어야 의미가 있다.
   * 두 번째 인자는 현재 책에서 사용자가 명시적으로 설정한 [BookSettingsOverrides]만 — 미설정 필드는 형제 권의 전역 기본값을 따른다.
   */
  onNavigate: (BookRef, BookSettingsOverrides) -> Unit = { _, _ -> },
) {
  val entry = context.current
  val localContext = LocalContext.current
  val appContext = localContext.applicationContext
  val db = remember(appContext) { PanelyDatabase.getInstance(appContext) }
  val positionRepo: PositionRepository = remember(db) { RoomPositionRepository(db) }
  val bookSettingsRepo: BookSettingsRepository = remember(db) { RoomBookSettingsRepository(db) }
  val bookmarkRepo: BookmarkRepository = remember(db) { RoomBookmarkRepository(db) }
  val appPrefsRepo: AppPreferencesRepository = remember(appContext) {
    SharedPrefsAppPreferencesRepository(appContext)
  }
  BackHandler { onBack() }

  ReaderSystemBarsEffect()

  var loadingStep by remember { mutableStateOf(localContext.getString(R.string.reader_loading_opening)) }
  // produceState 키는 nested 책까지 구분하는 bookIdSource를 사용 — 일반 책은
  // documentUri와 동일, ZIP-of-CBZ 자식은 `${uri}#${entry}`라 형제 권 간 이동 시 새 책으로 정확히 재진입.
  val sessionState by produceState<SessionState>(
    SessionState.Loading,
    entry.bookIdSource,
    initialPageOverride,
  ) {
    value = try {
      Log.d(TAG, "ReaderScreen open: ${entry.displayName}")
      loadingStep = localContext.getString(R.string.reader_loading_scan_pages)
      closeResourceOnFailure(
        open = { CbzBookSession.open(appContext, entry) },
        close = { it.close() },
      ) { session ->
        loadingStep = localContext.getString(R.string.reader_loading_restore_position)
        // Room에서 마지막 페이지를 미리 로드한다. ReaderViewModel 생성 시점이 동기라
        // 여기서 비동기로 받아 Ready에 묶어서 넘긴다. 페이지 수 줄어든 책은 clamp.
        val resumed = initialPageOverride?.coerceIn(0, session.pageCount - 1)
          ?: positionRepo.load(session.bookId)
            ?.pageIndex
            ?.coerceIn(0, session.pageCount - 1)
          ?: 0
        // 전역 prefs(기본 읽기 방향/두쪽/회전/흑백 반전/풀리프레시)는 SharedPreferences 1회 read.
        val appPrefs = appPrefsRepo.load()
        // sparse override 모델 — DB에 책별 row가 있으면 그것만 명시 override로,
        // 없으면 propagated(시리즈 형제 권 전파)도 없으면 NONE.
        // 모든 미설정 필드는 [resolve]가 appPrefs로 합성. 전역 변경이 항상 책에 흘러들어옴.
        val initialOverrides = bookSettingsRepo.load(session.bookId)
          ?: propagatedOverrides
          ?: BookSettingsOverrides.NONE
        val bookSettings = initialOverrides.resolve(appPrefs)
        loadingStep =
          localContext.getString(R.string.reader_loading_decode_first_page, session.pageCount)
        session.decode(resumed, trimEnabled = bookSettings.trimEnabled)
        Log.d(TAG, "ReaderScreen ready (resume page=$resumed)")
        SessionState.Ready(
          session = session,
          resumedPage = resumed,
          bookSettings = bookSettings,
          initialOverrides = initialOverrides,
          appPreferences = appPrefs,
        )
      }
    } catch (cancel: CancellationException) {
      // 코루틴 취소는 다시 던져야 한다 — Failed로 잡으면 화면이 잘못 표시됨
      throw cancel
    } catch (t: Throwable) {
      Log.e(TAG, "ReaderScreen open failed: ${entry.displayName}", t)
      val cls = t.javaClass.simpleName
      val msg = t.message ?: localContext.getString(R.string.reader_error_no_message)
      SessionState.Failed("$cls: $msg")
    }
  }

  Box(
    modifier = Modifier
      .fillMaxSize()
      .background(PanelyInkColors.Paper),
  ) {
    when (val s = sessionState) {
      SessionState.Loading -> ReaderLoading(loadingStep)
      is SessionState.Failed -> ReaderError(message = s.message)
      is SessionState.Ready -> ReaderContent(
        session = s.session,
        bookTitle = entry.displayName,
        resumedPage = s.resumedPage,
        initialBookSettings = s.bookSettings,
        initialOverrides = s.initialOverrides,
        initialAppPreferences = s.appPreferences,
        positionRepo = positionRepo,
        bookSettingsRepo = bookSettingsRepo,
        bookmarkRepo = bookmarkRepo,
        appPrefsRepo = appPrefsRepo,
        context = context,
        onNavigate = onNavigate,
        onExit = onBack,
      )
    }
  }
}

internal suspend fun <T, R> closeResourceOnFailure(
  open: suspend () -> T,
  close: (T) -> Unit,
  block: suspend (T) -> R,
): R {
  var resource: T? = null
  try {
    val opened = open()
    resource = opened
    val result = block(opened)
    resource = null
    return result
  } catch (cancel: CancellationException) {
    resource?.let { runCatching { close(it) } }
    throw cancel
  } catch (t: Throwable) {
    resource?.let { runCatching { close(it) } }
    throw t
  }
}

private const val TAG = "PanelyInk.Reader"

@Composable
private fun ReaderContent(
  session: CbzBookSession,
  bookTitle: String,
  resumedPage: Int,
  initialBookSettings: BookSettings,
  initialOverrides: BookSettingsOverrides,
  initialAppPreferences: AppPreferences,
  positionRepo: PositionRepository,
  bookSettingsRepo: BookSettingsRepository,
  bookmarkRepo: BookmarkRepository,
  appPrefsRepo: AppPreferencesRepository,
  context: SeriesContext,
  onNavigate: (BookRef, BookSettingsOverrides) -> Unit,
  onExit: () -> Unit,
) {
  // ViewModelStore에 캐시되지 않도록 remember(session)로 묶는다 — 책을 닫고
  // 다시 열 때 stale decoder(닫힌 ZipFile)를 잡는 문제 방지.
  val viewModel = remember(session) {
    // 마지막 페이지/책별 설정/전역 prefs는 produceState 안에서 이미 로드됨.
    ReaderViewModel(
      bookId = session.bookId,
      pageCount = session.pageCount,
      decoder = session.asDecoder(),
      initialPage = resumedPage,
      initialBookSettings = initialBookSettings,
      initialOverrides = initialOverrides,
      initialAppPreferences = initialAppPreferences,
    )
  }

  ReaderLifecycleEffect(session = session, viewModel = viewModel)

  val state by viewModel.state.collectAsState()
  // 책별 회전 잠금 — 본문 화면 라이프사이클 동안만 Activity에 적용.
  ReaderOrientationEffect(state.orientation)
  var view by remember { mutableStateOf<ReaderView?>(null) }
  var menuOpen by remember { mutableStateOf(false) }
  var settingsOpen by remember { mutableStateOf(false) }
  var bookmarksOpen by remember { mutableStateOf(false) }
  var bookmarkedPages by remember(session) { mutableStateOf<Set<Int>>(emptySet()) }
  val uiScope = rememberCoroutineScope()

  ReaderBackHandler(
    menuOpen = menuOpen,
    settingsOpen = settingsOpen,
    bookmarksOpen = bookmarksOpen,
    onCloseMenu = { menuOpen = false },
    onCloseBookmarks = { bookmarksOpen = false },
  )
  ReaderViewEffects(state = state, viewModel = viewModel, view = view)
  ReaderPersistenceEffects(
    viewModel = viewModel,
    positionRepo = positionRepo,
    bookSettingsRepo = bookSettingsRepo,
    appPrefsRepo = appPrefsRepo,
  )
  LaunchedEffect(session) {
    bookmarkedPages = bookmarkRepo.loadPages(session.bookId).toSet()
  }
  ReaderHardwareKeyHandler(
    state = state,
    viewModel = viewModel,
    context = context,
    menuOpen = menuOpen,
    settingsOpen = settingsOpen,
    bookmarksOpen = bookmarksOpen,
    onCloseMenu = { menuOpen = false },
    onCloseSettings = { settingsOpen = false },
    onCloseBookmarks = { bookmarksOpen = false },
    onNavigate = onNavigate,
  )

  // 본문 트리 전체를 회전 — 물리 viewport와 state.orientation이 mismatch일 때만 90° CW 적용.
  // ReaderView의 페이지 그림과 ReaderOverlayLayer의 메뉴/탭 영역이 한 덩어리로 회전되어
  // Compose hit-test가 pointer 좌표를 자동 재맵핑한다.
  ReaderRotationLayout(orientation = state.orientation) {
    ReaderAndroidViewHost(
      session = session,
      viewModel = viewModel,
      onViewAttached = { view = it },
      onViewReleased = { released ->
        if (view === released) view = null
      },
    )
    ReaderOverlayLayer(
      state = state,
      viewModel = viewModel,
      context = context,
      bookTitle = bookTitle,
      menuOpen = menuOpen,
      settingsOpen = settingsOpen,
      bookmarksOpen = bookmarksOpen,
      currentPageBookmarked = state.currentPage in bookmarkedPages,
      bookmarkedPages = bookmarkedPages,
      onOpenMenu = { menuOpen = true },
      onCloseMenu = { menuOpen = false },
      onOpenSettings = { settingsOpen = true },
      onCloseSettings = { settingsOpen = false },
      onOpenBookmarks = { bookmarksOpen = true },
      onCloseBookmarks = { bookmarksOpen = false },
      onExit = onExit,
      onJumpToPage = { page ->
        viewModel.goTo(page)
        menuOpen = false
      },
      onToggleBookmark = {
        val page = state.currentPage
        uiScope.launch {
          if (page in bookmarkedPages) {
            bookmarkRepo.remove(session.bookId, page)
            bookmarkedPages = bookmarkedPages - page
          } else {
            bookmarkRepo.add(session.bookId, page)
            bookmarkedPages = bookmarkedPages + page
          }
        }
      },
      onDeleteBookmark = { page ->
        uiScope.launch {
          bookmarkRepo.remove(session.bookId, page)
          bookmarkedPages = bookmarkedPages - page
        }
      },
      onNavigate = onNavigate,
    )
  }
}

@Composable
private fun ReaderLoading(message: String) {
  val typography = LocalPanelyInkTypography.current
  Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
    Text(
      text = message,
      style = typography.body,
      color = PanelyInkColors.Ink,
    )
  }
}

@Composable
private fun ReaderError(message: String) {
  val typography = LocalPanelyInkTypography.current
  val spacing = LocalPanelyInkSpacing.current
  Box(
    modifier = Modifier
      .fillMaxSize()
      .padding(spacing.space5),
    contentAlignment = Alignment.Center,
  ) {
    Box(
      modifier = Modifier
        .border(4.dp, PanelyInkColors.Ink)
        .padding(spacing.space3),
    ) {
      Text(
        text = message,
        style = typography.body,
        color = PanelyInkColors.Ink,
        textAlign = TextAlign.Center,
      )
    }
  }
}

private sealed interface SessionState {
  data object Loading : SessionState
  data class Ready(
    val session: CbzBookSession,
    val resumedPage: Int,
    val bookSettings: BookSettings,
    val initialOverrides: BookSettingsOverrides,
    val appPreferences: AppPreferences,
  ) : SessionState

  data class Failed(val message: String) : SessionState
}
