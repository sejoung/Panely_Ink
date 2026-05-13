package io.github.sejoung.panelyink.library

import android.app.ActivityManager
import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.sejoung.panelyink.core.book.BookIdentity
import io.github.sejoung.panelyink.core.position.BookProgress
import io.github.sejoung.panelyink.data.AppDataResetter
import io.github.sejoung.panelyink.data.db.PanelyDatabase
import io.github.sejoung.panelyink.data.db.bookindex.BookIndexRepository
import io.github.sejoung.panelyink.data.db.bookindex.RoomBookIndexRepository
import io.github.sejoung.panelyink.data.db.bookmark.BookBookmark
import io.github.sejoung.panelyink.data.db.bookmark.BookmarkRepository
import io.github.sejoung.panelyink.data.db.bookmark.RoomBookmarkRepository
import io.github.sejoung.panelyink.data.db.cover.CoverStatus
import io.github.sejoung.panelyink.data.db.cover.RoomCoverMetaRepository
import io.github.sejoung.panelyink.data.db.position.RoomPositionRepository
import io.github.sejoung.panelyink.library.data.CoverCache
import io.github.sejoung.panelyink.library.data.CoverExtractor
import io.github.sejoung.panelyink.library.data.CoverPruner
import io.github.sejoung.panelyink.library.data.CoverState
import io.github.sejoung.panelyink.library.data.IndexedBookEntry
import io.github.sejoung.panelyink.library.data.LibraryPathCodec
import io.github.sejoung.panelyink.library.data.LibraryRepository
import io.github.sejoung.panelyink.library.data.NestedZipExtractor
import io.github.sejoung.panelyink.library.data.PrefetchedBookData
import io.github.sejoung.panelyink.library.data.SeriesLookup
import io.github.sejoung.panelyink.library.data.prefetchBookData
import io.github.sejoung.panelyink.library.data.sortLibraryEntries
import io.github.sejoung.panelyink.library.model.BookEntry
import io.github.sejoung.panelyink.library.model.FolderEntry
import io.github.sejoung.panelyink.library.model.LibraryEntry
import io.github.sejoung.panelyink.library.model.SortMode
import io.github.sejoung.panelyink.library.model.ViewMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * 라이브러리 화면용 상태 보유. 트리 탐색 모델:
 *
 * - [LibraryState.path] 가 비어있으면 root 목록(첫 화면)
 * - 비어있지 않으면 마지막 항목이 "현재 폴더", entries는 그 폴더의 직계
 *
 * SAF 권한은 root URI에만 부여 — 모든 자식 접근은 같은 권한 트리 안에서 일어난다.
 *
 * 영속 상태:
 * - `KEY_ROOTS`: 사용자가 추가한 SAF 트리 URI 집합
 * - `KEY_PATH`: 마지막으로 보던 path (JSON 직렬화). 앱 재시작 시 복원.
 *                  root가 사라졌거나 path가 비어있으면 무시.
 */
class LibraryViewModel(application: Application) : AndroidViewModel(application) {

  private val prefs =
    application.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
  private val repo = LibraryRepository(application)
  private val db = PanelyDatabase.getInstance(application)
  private val positionRepo = RoomPositionRepository(db)
  private val coverMetaRepo = RoomCoverMetaRepository(db)
  private val bookmarkRepo: BookmarkRepository = RoomBookmarkRepository(db)
  private val bookIndexRepo: BookIndexRepository = RoomBookIndexRepository(db)

  private val _state = MutableStateFlow(LibraryState())
  val state: StateFlow<LibraryState> = _state.asStateFlow()

  /** 현재 진행 중인 list/scan 작업. 폴더 진입 도중 새 진입이 들어오면 cancel. */
  private var listJob: Job? = null

  /**
   * ZIP-of-CBZ batch inspect 진행 중인 작업. 폴더 위/아래 이동 시 cancel하고 새로 시작.
   *
   * 화면에 보이는 .zip 책들을 백그라운드로 차례차례 검사 → 시리즈면 entries에서 BookEntry
   * 자리에 FolderEntry로 swap → 사용자에게 일관된 폴더 표시(권수/표지). 결과는
   * `LibraryRepository.seriesCache`에 영속(in-memory)되어 같은 폴더 재진입에서 즉시 응답.
   */
  private var inspectJob: Job? = null

  /** 시리즈 검사 동시 실행 수 — ZIP open(수백 ms) 비용. coverSemaphore와 별개. */
  private val inspectSemaphore = Semaphore(permits = 2)

  /**
   * 폴더 권수 카운트 진행 중인 작업. 동일 폴더에 대한 중복 요청을 dedup.
   * 화면 밖으로 나가도 작업은 그대로 끝까지 — 결과가 캐시에 들어가야 다음에 빠르다.
   */
  private val countingJobs = mutableMapOf<Uri, Job>()

  /** 표지 추출 진행 중인 작업. 동일 책에 대한 중복 요청 dedup. */
  private val coverJobs = mutableMapOf<String, Job>()

  /** 진행률 로드 진행 중인 작업. 동일 책에 대한 중복 요청 dedup. */
  private val progressJobs = mutableMapOf<String, Job>()

  /** 폴더의 첫 책(시리즈 표지) 찾기 진행 중인 작업. dedup. */
  private val folderCoverJobs = mutableMapOf<Uri, Job>()

  /**
   * 표지 추출 동시 실행 수 제한 — archive open(수백 ms)이 비싸 화면에 책 30권이
   * 동시에 등장할 때 모두 launch하면 IO 풀이 직렬화되며 첫 화면 채워지는 시간이 ↑.
   * Permit 2로 두면 화면 상단부터 보이는 행의 표지가 우선 빨리 채워짐.
   */
  private val coverSemaphore = Semaphore(permits = 2)
  private val countSemaphore = Semaphore(permits = 2)
  private val progressLoadedBookIds = mutableSetOf<String>()
  private val globalBookmarkBookIndex = mutableMapOf<String, IndexedBookEntry>()
  private var bookmarkPruneJob: Job? = null

  /**
   * 표지/메타 갱신 debounce 버퍼 — 추출 1장씩 도착할 때마다 [_state] copy + recompose
   * 시작하면 외장 SD에서 30번 stutter. 100ms 동안 도착한 것을 모아 한 번에 flush.
   *
   * Main 스레드 단일 컨텍스트에서만 접근(viewModelScope = Dispatchers.Main.immediate
   * + 모든 호출자가 launch 본문 마지막의 Main 복귀 시점) — plain MutableMap.
   */
  private val pendingCovers = mutableMapOf<String, ImageBitmap>()
  private val pendingCoverStatus = mutableMapOf<String, CoverStatus>()
  private var coverFlushJob: Job? = null

  private val coverMemoryCache = object : LinkedHashMap<String, ImageBitmap>(
    16,
    0.75f,
    true,
  ) {
    private var currentBytes = 0L
    private val maxBytes = coverMemoryCacheMaxBytes(application)

    override fun put(key: String, value: ImageBitmap): ImageBitmap? {
      val previous = super.put(key, value)
      if (previous != null) currentBytes -= previous.estimatedBytes
      currentBytes += value.estimatedBytes
      trimToSize()
      return previous
    }

    override fun clear() {
      super.clear()
      currentBytes = 0L
    }

    private fun trimToSize() {
      val iterator = entries.iterator()
      while (currentBytes > maxBytes && iterator.hasNext()) {
        val eldest = iterator.next()
        currentBytes -= eldest.value.estimatedBytes
        iterator.remove()
      }
    }
  }

  init {
    val savedRoots = prefs.getStringSet(KEY_ROOTS, emptySet()).orEmpty().map(Uri::parse)
    val savedPath = LibraryPathCodec.decode(prefs.getString(KEY_PATH, null))
      .takeIf { LibraryPathCodec.isValid(it, savedRoots) }
      .orEmpty()
    val savedSort = SortMode.fromKey(prefs.getString(KEY_SORT, null))
    val savedView = ViewMode.fromKey(prefs.getString(KEY_VIEW, null))
    _state.value = _state.value.copy(
      roots = savedRoots,
      path = savedPath,
      sortMode = savedSort,
      viewMode = savedView,
    )
    if (savedPath.isNotEmpty()) {
      // 마지막 위치 복원 — 자동 진입(autoDescend)은 비활성, 직접 children 로드.
      refreshChildren(savedPath.last())
    } else {
      refreshRoots(autoDescend = true)
    }
  }

  /**
   * 사용자 명시 "전체 초기화" — Room/Prefs/SAF 권한/디스크 캐시 모두 비움.
   * 신규 사용자 상태로 복귀. state도 [LibraryState] 기본값으로 갱신해 화면이
   * 빈 상태로 보이도록.
   */
  fun resetAllData() {
    viewModelScope.launch {
      // 진행 중인 작업 모두 취소 — 이후 빈 state에 누락된 결과가 반영되지 않게.
      listJob?.cancel()
      inspectJob?.cancel()
      countingJobs.values.forEach { it.cancel() }
      countingJobs.clear()
      cancelAndClearJobs(coverJobs)
      progressJobs.values.forEach { it.cancel() }
      progressJobs.clear()
      progressLoadedBookIds.clear()
      folderCoverJobs.values.forEach { it.cancel() }
      folderCoverJobs.clear()
      // cancelAndJoin: in-flight flushCoverUpdates가 끝난 뒤 버퍼를 비워 race-free 보장.
      coverFlushJob?.cancelAndJoin()
      pendingCovers.clear()
      pendingCoverStatus.clear()
      coverMemoryCache.clear()

      AppDataResetter.resetAll(getApplication(), db)
      repo.invalidateCache()
      _state.value = LibraryState()
    }
  }

  /**
   * 사용자 명시 "표지 캐시 비우기" — 디스크 파일 + 메타(FAILED 포함) 모두 삭제.
   * in-memory `state.covers`도 비워 다음 라이브러리 진입에서 모든 표지 재추출.
   */
  fun clearCoverCache() {
    viewModelScope.launch {
      cancelAndClearJobs(coverJobs)
      CoverPruner.clearAll(getApplication(), coverMetaRepo)
      // ZIP-of-CBZ 임시 추출 파일도 같이 정리(cacheDir/nested) — 표지 캐시와 함께
      // 사용자 의도 "캐시 비우기"에 포함.
      NestedZipExtractor.clearAll(getApplication())
      // 진행 중인 추출 작업도 무효 — 이미 launch된 것은 끝나며 새 메타에 OK 기록됨.
      // 일관성을 위해 in-memory state + debounce 버퍼 모두 비우고 다음 lazy 요청에서 재추출.
      // cancelAndJoin: 진행 중인 flushCoverUpdates가 있으면 끝까지 기다린 뒤 비운다.
      // 현 시점엔 Main.immediate 단일 디스패처라 사실상 동시 실행이 불가능하지만, 미래에
      // flushCoverUpdates가 suspend화되거나 디스패처가 바뀌어도 직렬화 의미를 유지.
      coverFlushJob?.cancelAndJoin()
      pendingCovers.clear()
      pendingCoverStatus.clear()
      coverMemoryCache.clear()
      _state.value = _state.value.copy(
        covers = emptyMap(),
        coverStatus = emptyMap(),
      )
    }
  }

  /** 표시 모드 변경 — entries 재정렬/재계산 없음, UI에서 분기. SharedPrefs 영속. */
  fun setViewMode(mode: ViewMode) {
    if (mode == _state.value.viewMode) return
    prefs.edit { putString(KEY_VIEW, mode.name) }
    _state.value = _state.value.copy(viewMode = mode)
  }

  /**
   * 검색어 변경 — 영속화하지 않음(검색 의도는 화면 단위 임시 상태).
   * 폴더 이동 시 자동으로 클리어되도록 [enterFolder]/[goUp]에서 비움.
   */
  fun setSearchQuery(query: String) {
    if (query == _state.value.searchQuery) return
    _state.value = _state.value.copy(searchQuery = query)
  }

  /**
   * 정렬 모드 변경. 현재 entries만 재정렬해 디스크 재스캔을 피함.
   * 선택은 SharedPreferences에 영속, 다음 실행에서 복원.
   */
  fun setSortMode(mode: SortMode) {
    if (mode == _state.value.sortMode) return
    prefs.edit { putString(KEY_SORT, mode.name) }
    viewModelScope.launch {
      val sorted = sortLibraryEntries(_state.value.entries, mode, positionRepo)
      _state.value = _state.value.copy(sortMode = mode, entries = sorted)
    }
  }

  fun addRoot(uri: Uri) {
    runCatching {
      getApplication<Application>().contentResolver.takePersistableUriPermission(
        uri,
        Intent.FLAG_GRANT_READ_URI_PERMISSION,
      )
    }
    val current = _state.value.roots
    if (current.any { it == uri }) return
    val next = current + uri
    persistRoots(next)
    _state.value = _state.value.copy(roots = next)
    // 새 root 1개로 시작했으면 자동 진입. 이미 다른 root에 들어와 있다면 그대로 둔다.
    if (next.size == 1 && _state.value.path.isEmpty()) {
      refreshRoots(autoDescend = true)
    } else if (_state.value.path.isEmpty()) {
      refreshRoots(autoDescend = false)
    }
    scheduleBookmarkPruneForCurrentRoots()
  }

  fun removeRoot(uri: Uri) {
    runCatching {
      getApplication<Application>().contentResolver.releasePersistableUriPermission(
        uri,
        Intent.FLAG_GRANT_READ_URI_PERMISSION,
      )
    }
    val next = _state.value.roots.filterNot { it == uri }
    globalBookmarkBookIndex.clear()
    persistRoots(next)
    // 사용자가 현재 진입해 있던 root가 사라졌으면 첫 화면으로 복귀.
    val newPath = _state.value.path.takeIf { it.firstOrNull()?.rootUri != uri }.orEmpty()
    setPath(newPath, alsoUpdateRoots = next)
    if (newPath.isEmpty()) {
      refreshRoots(autoDescend = next.size == 1)
    } else {
      refreshChildren(newPath.last())
    }
    if (_state.value.globalBookmarksOpen) {
      loadGlobalBookmarks()
    }
  }

  /**
   * 폴더 진입 — path에 push 후 그 폴더의 직계 로드. 검색어는 클리어.
   *
   * 로딩 중 더블 탭 보호: SAF `listFiles()` IPC가 큰 폴더에서 수백 ms~초 단위로
   * 걸리는데, 그동안 화면은 이전 entries 그대로 보인다. 사용자가 같은 폴더 행을
   * 또 누르면 [_state.value.path]에 같은 [folder]가 쌓여 `[.., X, X, X]`처럼
   * 중복 누적 — 탈출 시 그만큼 ← back을 여러 번 눌러야 한다.
   *
   * 마지막 항목의 `documentUri`가 같으면 신규 push를 무시. 같은 이름이라도
   * `documentUri`는 SAF 내부에서 고유해 안전.
   */
  fun enterFolder(folder: FolderEntry) {
    val current = _state.value.path
    if (current.lastOrNull()?.documentUri == folder.documentUri) return
    val nextPath = current + folder
    setPath(nextPath)
    clearSearch()
    refreshChildren(folder)
  }

  /**
   * 사용자가 책 행을 클릭 — ZIP-of-CBZ 검사 후 분기. PRD §6.1 "중첩 아카이브 추출".
   *
   * - 일반 책: [onBook] 콜백 (호출자가 ReaderScreen 진입 처리)
   * - 시리즈 ZIP: 가상 폴더로 path push (라이브러리 자식 .cbz 목록)
   * - 이미 nested 책: 즉시 [onBook] (재검사 X)
   *
   * 검사는 비동기 — 외장 SD에서 100~500ms 사용자 짧은 대기.
   */
  fun openBook(book: BookEntry, onBook: (BookEntry) -> Unit) {
    if (book.nestedEntryName != null) {
      progressLoadedBookIds.remove(BookIdentity.fromEntry(book).value)
      onBook(book)
      return
    }
    viewModelScope.launch {
      val virtualFolder = repo.inspectZipForSeries(book)
      if (virtualFolder != null) {
        val nextPath = _state.value.path + virtualFolder
        setPath(nextPath)
        clearSearch()
        refreshChildren(virtualFolder)
      } else {
        progressLoadedBookIds.remove(BookIdentity.fromEntry(book).value)
        onBook(book)
      }
    }
  }

  /**
   * 한 단계 위로. 이미 root 목록이면 false 반환(상위가 없음 — caller가 시스템 뒤로 처리).
   * 검색어는 클리어 — 위/아래 이동은 컨텍스트 변경.
   */
  fun goUp(): Boolean {
    val current = _state.value.path
    if (current.isEmpty()) return false
    val nextPath = current.dropLast(1)
    setPath(nextPath)
    clearSearch()
    if (nextPath.isEmpty()) {
      // 사용자 의도로 root 목록까지 올라온 경우 — 자동 진입 X
      refreshRoots(autoDescend = false)
    } else {
      refreshChildren(nextPath.last())
    }
    return true
  }

  private fun clearSearch() {
    if (_state.value.searchQuery.isNotEmpty()) {
      _state.value = _state.value.copy(searchQuery = "")
    }
  }

  /** 명시적 새로고침 — listChildren in-memory 캐시 비우고 같은 위치 다시 스캔. */
  fun refresh() {
    repo.invalidateCache()
    globalBookmarkBookIndex.clear()
    val current = _state.value.path.lastOrNull()
    if (current == null) refreshRoots(autoDescend = false) else refreshChildren(current)
    scheduleBookmarkPruneForCurrentRoots()
  }

  fun openGlobalBookmarks() {
    _state.value = _state.value.copy(globalBookmarksOpen = true)
    loadGlobalBookmarks()
  }

  fun closeGlobalBookmarks() {
    _state.value = _state.value.copy(globalBookmarksOpen = false)
  }

  fun loadGlobalBookmarks() {
    viewModelScope.launch {
      _state.value = _state.value.copy(globalBookmarksLoading = true)
      runCatching { loadGlobalBookmarkItems() }
        .onSuccess { items ->
          _state.value = _state.value.copy(
            globalBookmarks = items,
            globalBookmarksLoading = false,
          )
        }
        .onFailure {
          _state.value = _state.value.copy(
            globalBookmarks = emptyList(),
            globalBookmarksLoading = false,
          )
        }
    }
  }

  fun deleteGlobalBookmark(item: GlobalBookmarkItem) {
    viewModelScope.launch {
      bookmarkRepo.remove(item.bookId, item.pageIndex)
      _state.value = _state.value.copy(
        globalBookmarks = _state.value.globalBookmarks.filterNot {
          it.bookId == item.bookId && it.pageIndex == item.pageIndex
        },
      )
    }
  }

  /**
   * 폴더 행이 화면에 등장할 때 호출 — [folder]의 책 권수를 lazy 계산해 캐시에 적재.
   * 이미 캐시에 있거나 계산 중이면 즉시 반환. 결과는 [LibraryState.folderCounts] 갱신.
   */
  fun requestFolderCount(folder: FolderEntry) {
    val key = folder.documentUri
    if (_state.value.folderCounts.containsKey(key)) return
    // ZIP-of-CBZ 가상 폴더 — nestedBooks.size 그대로(검사 시점에 이미 결정). countBooks 호출 X.
    if (folder.nestedBooks != null) {
      _state.value = _state.value.copy(
        folderCounts = _state.value.folderCounts + (key to folder.nestedBooks.size),
      )
      return
    }
    if (countingJobs.containsKey(key)) return
    val job = viewModelScope.launch {
      try {
        val count = countSemaphore.withPermit {
          repo.countBooks(folder)
        }
        _state.value = _state.value.copy(
          folderCounts = _state.value.folderCounts + (key to count),
        )
      } finally {
        countingJobs.remove(key)
      }
    }
    countingJobs[key] = job
  }

  /**
   * 폴더 행이 화면에 등장할 때 호출 — 시리즈 그룹핑(폴더=시리즈) 표시용으로 폴더
   * 안 첫 책을 찾고 그 책의 표지를 추출. 결과는 [LibraryState.folderFirstBook]
   * (folder.uri → bookId)에 저장하고, 표지 자체는 일반 [requestCover] 흐름을 재사용해
   * `state.covers[bookId]`에 들어간다 — 책 행이 같은 책일 때 비트맵 1개만 메모리.
   *
   * 빈 폴더(또는 폴더만 있는 폴더)는 firstBook=null이라 매핑 안 함 → 폴더 아이콘 fallback.
   */
  fun requestFolderCover(folder: FolderEntry) {
    val key = folder.documentUri
    val knownBookId = _state.value.folderFirstBook[key]
    if (knownBookId != null) {
      if (_state.value.covers.containsKey(knownBookId)) return
      _state.value.folderFirstBookEntries[key]?.let { firstBook ->
        requestCover(firstBook)
      }
      return
    }
    if (folderCoverJobs.containsKey(key)) return
    val job = viewModelScope.launch {
      try {
        // 가상 폴더면 첫 nested 책이 곧 표지 source — repo.firstBookIn(SAF query) 생략.
        val firstBook = folder.nestedBooks?.firstOrNull() ?: repo.firstBookIn(folder)
        if (firstBook != null) {
          val bookId = BookIdentity.fromEntry(firstBook).value
          _state.value = _state.value.copy(
            folderFirstBook = _state.value.folderFirstBook + (key to bookId),
            folderFirstBookEntries = _state.value.folderFirstBookEntries + (key to firstBook),
          )
          // 그 책의 표지 추출 — covers map 공유. 책 행과 폴더 행이 같은 비트맵 사용.
          requestCover(firstBook)
        }
      } finally {
        folderCoverJobs.remove(key)
      }
    }
    folderCoverJobs[key] = job
  }

  /**
   * 책 행이 화면에 등장할 때 호출 — Room에서 진행률을 lazy 로드해 캐시.
   *
   * 미열람 책은 Room에 row 자체가 없어 결과 null → covers와 달리 map에 키 자체를
   * 추가하지 않는다(라이브러리 라벨 표시 X). 사용자가 한 번이라도 책을 열면 다음
   * 라이브러리 진입에서 % 라벨이 등장.
   */
  fun requestProgress(book: BookEntry) {
    val bookId = BookIdentity.fromEntry(book).value
    if (bookId in progressLoadedBookIds) return
    if (progressJobs.containsKey(bookId)) return
    val job = viewModelScope.launch {
      try {
        val progress = positionRepo.load(bookId)
        progressLoadedBookIds += bookId
        if (progress != null && progress.isKnown) {
          _state.value = _state.value.copy(
            bookProgress = _state.value.bookProgress + (bookId to progress),
          )
        }
      } finally {
        progressJobs.remove(bookId)
      }
    }
    progressJobs[bookId] = job
  }

  /**
   * 책 행이 화면에 등장할 때 호출 — 표지를 lazy 추출해 캐시에 적재.
   *
   * 흐름 (M3 CoverMeta 도입 후):
   * 1. in-memory hit이면 즉시 반환
   * 2. CoverMeta 확인 — status=FAILED면 영구 skip(깨진 책 재시도 방지)
   * 3. 디스크 hit + 메타 OK이면 비트맵 디코드 → in-memory 추가
   * 4. 둘 다 miss/손상이면 [CoverExtractor] 시도
   *    - 성공 → 디스크 저장 + 메타 OK + in-memory 추가
   *    - 실패(null) → 메타 FAILED — 다음부터 skip
   *
   * archive open은 수백 ms 비용이라 한 번 실패한 책에 매번 재시도하면 라이브러리
   * 첫 진입이 답답해진다. 사용자 명시 새로고침으로만 재추출(`coverMetaRepo.delete`).
   */
  fun requestCover(book: BookEntry) {
    val bookId = BookIdentity.fromEntry(book).value
    if (_state.value.covers.containsKey(bookId)) return
    coverMemoryCache[bookId]?.let { image ->
      _state.value = _state.value.copy(covers = _state.value.covers + (bookId to image))
      return
    }
    if (coverJobs.containsKey(bookId)) return
    // batch 캐시 hit: status=FAILED면 즉시 종료, 추가 Room query 없음.
    if (_state.value.coverStatus[bookId] == CoverStatus.FAILED) return
    val app = getApplication<Application>()
    val job = viewModelScope.launch {
      try {
        val cachedStatus = _state.value.coverStatus[bookId]
        val cached = withContext(Dispatchers.IO) {
          // batch 캐시에 OK가 있으면 단건 메타 query 생략 → 디스크 read만.
          val meta = if (cachedStatus != null) {
            cachedStatus
          } else {
            val loaded = coverMetaRepo.load(bookId)?.status
            if (loaded == CoverStatus.FAILED) return@withContext null to true
            loaded
          }
          val file = CoverCache.cacheFile(app, bookId)
          if (meta == CoverStatus.OK) {
            CoverCache.loadBitmap(file)?.let { return@withContext it to true }
          }
          null to false  // 추출 필요
        }
        val (cachedBitmap, done) = cached
        val image = if (done) {
          cachedBitmap
        } else {
          // 추출은 archive open이 비싸 동시 N개 제한 (전역 semaphore).
          coverSemaphore.withPermit {
            withContext(Dispatchers.IO) {
              val file = CoverCache.cacheFile(app, bookId)
              val extracted = if (book.nestedEntryName != null) {
                // ZIP-of-CBZ 자식 표지 — 1순위 reader 추출 캐시(빠름),
                // 2순위 부모 ZIP에서 nested cbz를 streaming으로 첫 image bytes만
                // 메모리 디코드. 디스크 추출 없이 라이브러리에서도 표지 가능.
                val nestedFile = NestedZipExtractor.cacheFile(
                  app, book.documentUri, book.nestedEntryName,
                )
                if (nestedFile.exists() && nestedFile.length() > 0) {
                  CoverExtractor.extract(nestedFile)
                } else {
                  CoverExtractor.extractNestedCover(
                    app, book.documentUri, book.nestedEntryName,
                  )
                }
              } else {
                CoverExtractor.extract(app, book.documentUri)
              }
              if (extracted != null) {
                CoverCache.saveBitmap(file, extracted)
                coverMetaRepo.save(bookId, CoverStatus.OK)
              } else {
                // 추출 실패 → FAILED 메타 영구 저장. nested 책도 stream 추출까지 실패한 거라
                // 깨진 책으로 간주해 다음 진입에서 재시도 안 함.
                coverMetaRepo.save(bookId, CoverStatus.FAILED)
              }
              extracted
            }
          }
        }?.asImageBitmap()
        val newStatus = if (image != null) CoverStatus.OK else CoverStatus.FAILED
        if (done) {
          applyCoverUpdateNow(bookId, image, newStatus)
        } else {
          queueCoverUpdate(bookId, image, newStatus)
        }
      } finally {
        // cancel/예외 케이스에도 dedup map 정리 — 다음 요청에서 재시도 가능.
        coverJobs.remove(bookId)
      }
    }
    coverJobs[bookId] = job
  }

  /**
   * @param autoDescend root가 1개일 때 그 root 안으로 자동 진입할지. 호출 시점에 호출자의
   * 의도가 캡처되어 비동기 시점의 race를 차단한다. 이전엔 멤버 필드 `pendingAutoDescend`로
   * 흘렸는데, addRoot/removeRoot가 빠르게 연속 호출될 때 첫 launch 본문이 두 번째 호출의
   * 필드 변경을 보는 추적 어려운 상황을 만들었음.
   */
  private fun refreshRoots(autoDescend: Boolean) {
    listJob?.cancel()
    listJob = viewModelScope.launch {
      _state.value = _state.value.copy(scanning = true)
      val rootEntries = repo.listRoots(_state.value.roots)

      // 자동 진입: root 1개면 한 프레임도 root 화면을 노출하지 않고
      // 바로 그 안 children 로드 — e-ink 깜빡임 방지.
      if (autoDescend && rootEntries.size == 1 && _state.value.path.isEmpty()) {
        val target = rootEntries.first()
        setPath(listOf(target))
        val children = repo.listChildren(target)
        val sorted = sortLibraryEntries(children, _state.value.sortMode, positionRepo)
        pruneCoversToVisibleBooks(sorted)
        _state.value = _state.value.copy(entries = sorted, scanning = false)
        applyPrefetchedBookData(prefetchBookData(sorted, positionRepo, coverMetaRepo))
        applyCachedEntryCovers(sorted)
        batchInspectSeries()
      } else {
        // root 자체는 폴더라 sortMode와 무관하게 이름순(repo가 이미 정렬).
        pruneCoversToVisibleBooks(rootEntries)
        _state.value = _state.value.copy(entries = rootEntries, scanning = false)
      }
    }
  }

  private fun refreshChildren(folder: FolderEntry) {
    listJob?.cancel()
    listJob = viewModelScope.launch {
      _state.value = _state.value.copy(scanning = true)
      val children = repo.listChildren(folder)
      val sorted = sortLibraryEntries(children, _state.value.sortMode, positionRepo)
      pruneCoversToVisibleBooks(sorted)
      _state.value = _state.value.copy(entries = sorted, scanning = false)
      applyPrefetchedBookData(prefetchBookData(sorted, positionRepo, coverMetaRepo))
      applyCachedEntryCovers(sorted)
      batchInspectSeries()
    }
  }

  /**
   * 폴더 이동 시 state.covers를 새 폴더에 보이는 책+폴더-첫책 bookId 집합으로 좁힌다.
   *
   * cover state는 hot path에서 additive로 갱신되어 같은 폴더에서 무한 루프(LRU evict ↔
   * `LaunchedEffect(cover == null)` 재발화)를 막는 대신, 폴더 간 이동에서 누적되는 메모리를
   * 이 함수가 폴더 단위로 회수한다. coverMemoryCache는 그대로 둠 — 다음 방문 시 디스크
   * skip path로 즉시 복원.
   */
  private fun pruneCoversToVisibleBooks(entries: List<LibraryEntry>) {
    val current = _state.value.covers
    if (current.isEmpty()) return
    val visibleBookIds = CoverState.visibleBookIdsFor(entries, _state.value.folderFirstBook)
    val pruned = CoverState.pruneCoversToVisible(current, visibleBookIds)
    if (pruned !== current && pruned.size != current.size) {
      _state.value = _state.value.copy(covers = pruned)
    }
  }

  /**
   * 현재 entries의 .zip BookEntry들을 백그라운드로 시리즈 검사. 시리즈면 entries 안에서
   * 같은 자리에 FolderEntry로 swap — 권수/표지가 일반 폴더와 동일한 흐름으로 표시.
   *
   * .cbz는 일반적으로 단일 책이라 검사 대상에서 제외(첫 진입 비용 절감). 사용자가 .cbz
   * 시리즈를 만든 케이스는 클릭 시 [openBook] 분기에서 검사.
   *
   * 점진적 표시: 결과 도착할 때마다 entries 갱신 → 화면 상단 zip부터 폴더로 변신.
   */
  private fun batchInspectSeries() {
    inspectJob?.cancel()
    val candidates = _state.value.entries
      .filterIsInstance<BookEntry>()
      .filter {
        it.nestedEntryName == null &&
          it.displayName.endsWith(".zip", ignoreCase = true)
      }
    if (candidates.isEmpty()) return

    // cache hit pre-filter: 이전에 검사된 책은 launch/semaphore/IO 없이 동기로 처리.
    // hit 결과들은 한 번의 entries copy로 합쳐 recompose 1회로 끝낸다. 미검사(miss)만
    // 코루틴으로 보내 실제 ZIP open을 돌린다 — 폴더 안 .zip 50권이 모두 캐시된 경우
    // 이전엔 50회 launch + semaphore acquire를 낭비했음.
    val cachedHits = mutableMapOf<Uri, FolderEntry>()
    val misses = mutableListOf<BookEntry>()
    for (book in candidates) {
      when (val lookup = repo.seriesCacheLookup(book)) {
        is SeriesLookup.Hit -> {
          // result=null은 "검사했고 시리즈 아님" — swap 불필요.
          lookup.result?.let { cachedHits[book.documentUri] = it }
        }

        SeriesLookup.Miss -> misses += book
        SeriesLookup.NotApplicable -> { /* filter에서 이미 제외돼 도달 불가 */
        }
      }
    }

    if (cachedHits.isNotEmpty()) {
      val current = _state.value.entries
      val updated = current.map { e ->
        if (e is BookEntry && e.nestedEntryName == null) {
          cachedHits[e.documentUri] ?: e
        } else e
      }
      if (updated != current) {
        _state.value = _state.value.copy(entries = updated)
      }
    }

    if (misses.isEmpty()) return
    inspectJob = viewModelScope.launch {
      for (book in misses) {
        launch {
          val result = inspectSemaphore.withPermit { repo.inspectZipForSeries(book) }
            ?: return@launch
          // 결과 도착 시점의 최신 entries에서 swap — 그 사이 사용자가 폴더 이동했으면
          // entries가 다른 목록이라 swap이 일치하지 않을 수 있는데, listJob.cancel +
          // inspectJob.cancel로 새 폴더에선 새 inspect가 시작되므로 이전 결과는 무시 OK.
          val current = _state.value.entries
          val updated = current.map { e ->
            if (
              e is BookEntry &&
              e.nestedEntryName == null &&
              e.documentUri == book.documentUri
            ) result else e
          }
          if (updated != current) {
            _state.value = _state.value.copy(entries = updated)
          }
        }
      }
    }
  }

  /**
   * 표지/메타 갱신을 debounce 버퍼에 추가. 100ms 동안 도착한 것들이 한 번의 state
   * copy로 합쳐져 recompose 횟수가 30번 → 1~2번 수준으로 줄어든다.
   *
   * 첫 도착 시 flush 코루틴 launch — 이미 진행 중이면 buffer에만 추가하고 그 코루틴이
   * 다음 flush에서 같이 처리.
   */
  private fun queueCoverUpdate(
    bookId: String,
    image: ImageBitmap?,
    status: CoverStatus,
  ) {
    if (image != null) pendingCovers[bookId] = image
    pendingCoverStatus[bookId] = status
    if (coverFlushJob?.isActive == true) return
    coverFlushJob = viewModelScope.launch {
      delay(COVER_FLUSH_DEBOUNCE_MS)
      flushCoverUpdates()
    }
  }

  private fun applyCoverUpdateNow(
    bookId: String,
    image: ImageBitmap?,
    status: CoverStatus,
  ) {
    if (image != null) coverMemoryCache[bookId] = image
    val incoming = if (image != null) mapOf(bookId to image) else emptyMap()
    _state.value = _state.value.copy(
      covers = CoverState.mergeCovers(_state.value.covers, incoming),
      coverStatus = CoverState.mergeCoverStatuses(
        _state.value.coverStatus,
        mapOf(bookId to status),
      ),
    )
  }

  private fun flushCoverUpdates() {
    if (pendingCovers.isEmpty() && pendingCoverStatus.isEmpty()) return
    val covers = pendingCovers.toMap()
    val status = pendingCoverStatus.toMap()
    pendingCovers.clear()
    pendingCoverStatus.clear()
    covers.forEach { (bookId, image) ->
      coverMemoryCache[bookId] = image
    }
    _state.value = _state.value.copy(
      covers = CoverState.mergeCovers(_state.value.covers, covers),
      coverStatus = CoverState.mergeCoverStatuses(_state.value.coverStatus, status),
    )
  }

  private fun applyPrefetchedBookData(data: PrefetchedBookData) {
    if (data.progress.isNotEmpty() || data.coverStatus.isNotEmpty()) {
      _state.value = _state.value.copy(
        bookProgress = _state.value.bookProgress + data.progress,
        coverStatus = _state.value.coverStatus + data.coverStatus,
      )
    }
  }

  private suspend fun applyCachedEntryCovers(entries: List<LibraryEntry>) {
    val directBookIds = entries
      .filterIsInstance<BookEntry>()
      .map { BookIdentity.fromEntry(it).value }
    val folderBookIds = entries
      .filterIsInstance<FolderEntry>()
      .mapNotNull { folder -> _state.value.folderFirstBook[folder.documentUri] }
    val knownBookIds = (directBookIds + folderBookIds)
      .filterNot { bookId -> _state.value.covers.containsKey(bookId) }
      .distinct()
    if (knownBookIds.isEmpty()) return

    val memoryHits = knownBookIds.mapNotNull { bookId ->
      coverMemoryCache[bookId]?.let { bookId to it }
    }
    val memoryHitIds = memoryHits.mapTo(mutableSetOf()) { it.first }
    val diskIds = knownBookIds.filterNot { it in memoryHitIds }
    val app = getApplication<Application>()
    val diskHits = withContext(Dispatchers.IO) {
      diskIds.mapNotNull { bookId ->
        CoverCache.loadBitmap(CoverCache.cacheFile(app, bookId))
          ?.asImageBitmap()
          ?.let { bookId to it }
      }
    }
    val hits = memoryHits + diskHits
    if (hits.isEmpty()) return
    hits.forEach { (bookId, image) -> coverMemoryCache[bookId] = image }
    _state.value = _state.value.copy(
      covers = CoverState.mergeCovers(_state.value.covers, hits.toMap()),
      coverStatus = CoverState.mergeCoverStatuses(
        _state.value.coverStatus,
        hits.associate { it.first to CoverStatus.OK },
      ),
    )
  }

  private suspend fun loadGlobalBookmarkItems(): List<GlobalBookmarkItem> {
    val bookmarks = bookmarkRepo.loadAll()
    if (bookmarks.isEmpty()) return emptyList()
    val requestedBookIds = bookmarks.mapTo(mutableSetOf()) { it.bookId }
    val cached = globalBookmarkBookIndex.filterKeys { it in requestedBookIds }
    val missingBookIds = requestedBookIds - cached.keys
    val indexedHits = if (missingBookIds.isNotEmpty()) {
      bookIndexRepo.loadByIds(missingBookIds)
    } else {
      emptyList()
    }
    indexedHits.forEach { indexed ->
      globalBookmarkBookIndex[BookIdentity.fromEntry(indexed.book).value] = indexed
    }
    val scanMissingBookIds = requestedBookIds - globalBookmarkBookIndex.keys
    if (scanMissingBookIds.isNotEmpty()) {
      repo.findBooksByIds(_state.value.roots, scanMissingBookIds).also { found ->
        bookIndexRepo.upsertAll(found)
      }.forEach { indexed ->
        globalBookmarkBookIndex[BookIdentity.fromEntry(indexed.book).value] = indexed
      }
    }
    val indexedBooks = requestedBookIds.mapNotNull { globalBookmarkBookIndex[it] }
    return buildGlobalBookmarkItems(
      bookmarks = bookmarks,
      indexedBooks = indexedBooks,
    )
  }

  private suspend fun buildGlobalBookmarkItems(
    bookmarks: List<BookBookmark>,
    indexedBooks: List<IndexedBookEntry>,
  ): List<GlobalBookmarkItem> {
    val booksById = indexedBooks.associateBy { BookIdentity.fromEntry(it.book).value }
    bookmarkRepo.removeOrphans(booksById.keys)
    return bookmarks.mapNotNull { bookmark ->
      booksById[bookmark.bookId]?.let { indexed ->
        GlobalBookmarkItem(
          bookId = bookmark.bookId,
          book = indexed.book,
          siblings = indexed.siblings,
          pageIndex = bookmark.pageIndex,
          createdAt = bookmark.createdAt,
        )
      }
    }
  }

  private fun scheduleBookmarkPruneForCurrentRoots() {
    bookmarkPruneJob?.cancel()
    bookmarkPruneJob = viewModelScope.launch {
      delay(BOOKMARK_PRUNE_DEBOUNCE_MS)
      val indexedBooks = repo.listAllBooks(_state.value.roots)
      bookIndexRepo.replaceKnownBooks(indexedBooks)
      val existingBookIds = indexedBooks
        .mapTo(mutableSetOf()) { BookIdentity.fromEntry(it.book).value }
      bookmarkRepo.removeOrphans(existingBookIds)
      if (_state.value.globalBookmarksOpen) {
        _state.value = _state.value.copy(globalBookmarks = loadGlobalBookmarkItems())
      }
    }
  }

  /** path 변경의 단일 진입점. state 갱신 + 디스크 영속화를 한 곳에서 묶어 누락을 방지. */
  private fun setPath(newPath: List<FolderEntry>, alsoUpdateRoots: List<Uri>? = null) {
    _state.value = if (alsoUpdateRoots != null) {
      _state.value.copy(roots = alsoUpdateRoots, path = newPath)
    } else {
      _state.value.copy(path = newPath)
    }
    prefs.edit { putString(KEY_PATH, LibraryPathCodec.encode(newPath)) }
  }

  private fun persistRoots(roots: List<Uri>) {
    prefs.edit { putStringSet(KEY_ROOTS, roots.map(Uri::toString).toSet()) }
  }


  companion object {
    private const val COVER_FLUSH_DEBOUNCE_MS = 100L
    private const val BOOKMARK_PRUNE_DEBOUNCE_MS = 500L
    private const val PREFS_NAME = "panely_ink_prefs"
    private const val KEY_ROOTS = "library_root_uris"
    private const val KEY_PATH = "library_last_path"
    private const val KEY_SORT = "library_sort_mode"
    private const val KEY_VIEW = "library_view_mode"
  }
}

internal fun cancelAndClearJobs(jobs: MutableMap<String, Job>) {
  jobs.values.forEach { it.cancel() }
  jobs.clear()
}

private val ImageBitmap.estimatedBytes: Long
  get() = width.toLong() * height.toLong() * 4L

private fun coverMemoryCacheMaxBytes(application: Application): Long {
  val manager = application.getSystemService(ActivityManager::class.java)
  val memoryClassMb = manager?.memoryClass ?: DEFAULT_MEMORY_CLASS_MB
  val capMb = if (manager?.isLowRamDevice == true) LOW_RAM_COVER_CACHE_MB else MAX_COVER_CACHE_MB
  return minOf((memoryClassMb / 12).coerceAtLeast(MIN_COVER_CACHE_MB), capMb) * 1024L * 1024L
}

private const val DEFAULT_MEMORY_CLASS_MB = 256
private const val MIN_COVER_CACHE_MB = 8
private const val LOW_RAM_COVER_CACHE_MB = 12
private const val MAX_COVER_CACHE_MB = 24

/**
 * 라이브러리 화면이 관찰하는 불변 상태.
 *
 * - [path] 가 비어있으면 root 화면(`entries`는 root 폴더들)
 * - 비어있지 않으면 마지막이 "현재 폴더", `entries`는 그 폴더의 직계 자식들
 */
data class LibraryState(
  val roots: List<Uri> = emptyList(),
  val path: List<FolderEntry> = emptyList(),
  val entries: List<LibraryEntry> = emptyList(),
  val scanning: Boolean = false,
  /** 폴더 documentUri → 재귀 책 권수 (in-memory 캐시). 미계산 폴더는 키 없음. */
  val folderCounts: Map<Uri, Int> = emptyMap(),
  /** bookId(SHA-1 hex) → 표지 썸네일 ImageBitmap. 미추출 책은 키 없음. */
  val covers: Map<String, ImageBitmap> = emptyMap(),
  /**
   * 시리즈 그룹핑 — folder.documentUri → 그 폴더 안 첫 책의 bookId. 책 표지는
   * [covers]에서 같은 bookId로 lookup해 재사용(메모리 중복 X). 빈 폴더는 키 없음.
   */
  val folderFirstBook: Map<Uri, String> = emptyMap(),
  /** folder.documentUri → 첫 책 엔트리. 표지 캐시가 비워졌을 때 재추출 요청에 사용. */
  val folderFirstBookEntries: Map<Uri, BookEntry> = emptyMap(),
  /** bookId → 책 진행률(현재 페이지 + 전체). 미열람/모름 책은 키 없음. */
  val bookProgress: Map<String, BookProgress> = emptyMap(),
  /**
   * bookId → CoverMeta 상태 캐시. `prefetchBookData`에서 폴더 진입 시 batch로 한 번에
   * 채움 → `requestCover`는 캐시 우선 확인해 책당 추가 Room query 안 일어남.
   * 키 없음 = 메타 미존재(첫 추출 필요).
   */
  val coverStatus: Map<String, CoverStatus> = emptyMap(),
  /** 라이브러리 정렬 모드. 영속됨. */
  val sortMode: SortMode = SortMode.DEFAULT,
  /** 라이브러리 표시 모드(List/Cover/Grid). 영속됨. */
  val viewMode: ViewMode = ViewMode.DEFAULT,
  /** 현재 폴더 in-memory 검색어. 빈 문자열이면 필터 적용 X. 폴더 이동 시 자동 클리어. */
  val searchQuery: String = "",
  val globalBookmarksOpen: Boolean = false,
  val globalBookmarksLoading: Boolean = false,
  val globalBookmarks: List<GlobalBookmarkItem> = emptyList(),
)

data class GlobalBookmarkItem(
  val bookId: String,
  val book: BookEntry,
  val siblings: List<BookEntry>,
  val pageIndex: Int,
  val createdAt: Long,
)
