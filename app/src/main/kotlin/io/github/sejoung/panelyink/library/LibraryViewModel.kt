package io.github.sejoung.panelyink.library

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.sejoung.panelyink.core.position.BookProgress
import io.github.sejoung.panelyink.core.position.PositionKey
import io.github.sejoung.panelyink.core.sort.NaturalOrderComparator
import io.github.sejoung.panelyink.data.AppDataResetter
import io.github.sejoung.panelyink.data.db.CoverStatus
import io.github.sejoung.panelyink.data.db.PanelyDatabase
import io.github.sejoung.panelyink.data.db.RoomCoverMetaRepository
import io.github.sejoung.panelyink.data.db.RoomPositionRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * 라이브러리 화면용 상태 보유. 트리 탐색 모델:
 *
 * - [LibraryState.path] 가 비어있으면 root 목록(첫 화면)
 * - 비어있지 않으면 마지막 항목이 "현재 폴더", entries는 그 폴더의 직계
 *
 * SAF 권한은 root URI에만 부여 — 모든 자식 접근은 같은 권한 트리 안에서 일어난다.
 *
 * 영속 상태:
 * - [KEY_ROOTS]  : 사용자가 추가한 SAF 트리 URI 집합
 * - [KEY_PATH]   : 마지막으로 보던 path (JSON 직렬화). 앱 재시작 시 복원.
 *                  root가 사라졌거나 path가 비어있으면 무시.
 */
class LibraryViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = application.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
    private val repo = LibraryRepository(application)
    private val db = PanelyDatabase.getInstance(application)
    private val positionRepo = RoomPositionRepository(db)
    private val coverMetaRepo = RoomCoverMetaRepository(db)

    private val _state = MutableStateFlow(LibraryState())
    val state: StateFlow<LibraryState> = _state.asStateFlow()

    /** 현재 진행 중인 list/scan 작업. 폴더 진입 도중 새 진입이 들어오면 cancel. */
    private var listJob: Job? = null

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

    /**
     * 표지/메타 갱신 debounce 버퍼 — 추출 1장씩 도착할 때마다 [_state] copy + recompose
     * 시작하면 외장 SD에서 30번 stutter. 100ms 동안 도착한 것을 모아 한 번에 flush.
     *
     * Main 스레드 단일 컨텍스트에서만 접근(viewModelScope = Dispatchers.Main.immediate
     * + 모든 호출자가 launch 본문 마지막의 Main 복귀 시점) — plain MutableMap.
     */
    private val pendingCovers = mutableMapOf<String, ImageBitmap>()
    private val pendingCoverStatus = mutableMapOf<String, io.github.sejoung.panelyink.data.db.CoverStatus>()
    private var coverFlushJob: Job? = null

    /**
     * 다음 [refreshRoots] 시 자동으로 단일 root 안으로 한 단계 진입할지.
     *
     * - init / addRoot / removeRoot 직후에 켠다(사용자가 추가/제거한 결과 root가 1개로
     *   정리된 시점). root 1개면 첫 화면 = "폴더 1개" 짜리 의미 없는 단계라 건너뜀.
     * - [goUp]으로 사용자가 의도적으로 root 화면에 도달했을 땐 끈다 — 무한 루프 방지.
     * - 저장된 path가 복원되면 그쪽이 우선이라 자동 진입은 끈다.
     */
    private var pendingAutoDescend = false

    init {
        val savedRoots = prefs.getStringSet(KEY_ROOTS, emptySet()).orEmpty().map(Uri::parse)
        val savedPath = decodePath(prefs.getString(KEY_PATH, null))
            .takeIf { isPathValid(it, savedRoots) }
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
            // 마지막 위치 복원 — pendingAutoDescend는 비활성, 직접 children 로드.
            refreshChildren(savedPath.last())
        } else {
            pendingAutoDescend = true
            refreshRoots()
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
            countingJobs.values.forEach { it.cancel() }
            countingJobs.clear()
            coverJobs.values.forEach { it.cancel() }
            coverJobs.clear()
            progressJobs.values.forEach { it.cancel() }
            progressJobs.clear()
            folderCoverJobs.values.forEach { it.cancel() }
            folderCoverJobs.clear()
            coverFlushJob?.cancel()
            pendingCovers.clear()
            pendingCoverStatus.clear()

            AppDataResetter.resetAll(getApplication(), db)
            repo.invalidateCache()
            pendingAutoDescend = false
            _state.value = LibraryState()
        }
    }

    /**
     * 사용자 명시 "표지 캐시 비우기" — 디스크 파일 + 메타(FAILED 포함) 모두 삭제.
     * in-memory `state.covers`도 비워 다음 라이브러리 진입에서 모든 표지 재추출.
     */
    fun clearCoverCache() {
        viewModelScope.launch {
            CoverPruner.clearAll(getApplication(), coverMetaRepo)
            // ZIP-of-CBZ 임시 추출 파일도 같이 정리(cacheDir/nested) — 표지 캐시와 함께
            // 사용자 의도 "캐시 비우기"에 포함.
            NestedZipExtractor.clearAll(getApplication())
            // 진행 중인 추출 작업도 무효 — 이미 launch된 것은 끝나며 새 메타에 OK 기록됨.
            // 일관성을 위해 in-memory state + debounce 버퍼 모두 비우고 다음 lazy 요청에서 재추출.
            coverFlushJob?.cancel()
            pendingCovers.clear()
            pendingCoverStatus.clear()
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
            val sorted = applySort(_state.value.entries, mode)
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
            pendingAutoDescend = true
            refreshRoots()
        } else if (_state.value.path.isEmpty()) {
            refreshRoots()
        }
    }

    fun removeRoot(uri: Uri) {
        runCatching {
            getApplication<Application>().contentResolver.releasePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
        val next = _state.value.roots.filterNot { it == uri }
        persistRoots(next)
        // 사용자가 현재 진입해 있던 root가 사라졌으면 첫 화면으로 복귀.
        val newPath = _state.value.path.takeIf { it.firstOrNull()?.rootUri != uri }.orEmpty()
        setPath(newPath, alsoUpdateRoots = next)
        if (newPath.isEmpty()) {
            pendingAutoDescend = (next.size == 1)
            refreshRoots()
        } else {
            refreshChildren(newPath.last())
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
            pendingAutoDescend = false
            refreshRoots()
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
        val current = _state.value.path.lastOrNull()
        if (current == null) refreshRoots() else refreshChildren(current)
    }

    /**
     * 폴더 행이 화면에 등장할 때 호출 — [folder]의 책 권수를 lazy 계산해 캐시에 적재.
     * 이미 캐시에 있거나 계산 중이면 즉시 반환. 결과는 [LibraryState.folderCounts] 갱신.
     */
    fun requestFolderCount(folder: FolderEntry) {
        val key = folder.documentUri
        if (_state.value.folderCounts.containsKey(key)) return
        if (countingJobs.containsKey(key)) return
        val job = viewModelScope.launch {
            try {
                val count = repo.countBooks(folder)
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
        if (_state.value.folderFirstBook.containsKey(key)) return
        if (folderCoverJobs.containsKey(key)) return
        val job = viewModelScope.launch {
            try {
                val firstBook = repo.firstBookIn(folder)
                if (firstBook != null) {
                    val bookId = PositionKey.bookIdFromUri(firstBook.bookIdSource)
                    _state.value = _state.value.copy(
                        folderFirstBook = _state.value.folderFirstBook + (key to bookId),
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
        val bookId = PositionKey.bookIdFromUri(book.bookIdSource)
        if (_state.value.bookProgress.containsKey(bookId)) return
        if (progressJobs.containsKey(bookId)) return
        val job = viewModelScope.launch {
            try {
                val progress = positionRepo.load(bookId)
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
        val bookId = PositionKey.bookIdFromUri(book.bookIdSource)
        if (_state.value.covers.containsKey(bookId)) return
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
                                // ZIP-of-CBZ 자식 — reader에서 한 번 열려 추출 캐시가 있을 때만 표지 추출.
                                // 캐시 없으면 라이브러리 첫 진입에서 모든 nested cbz를 추출하는 비용이 너무 커서
                                // skip(다음 라이브러리 진입에서 재시도 가능, FAILED 영구 저장 X).
                                val nestedFile = NestedZipExtractor.cacheFile(
                                    app, book.documentUri, book.nestedEntryName,
                                )
                                if (nestedFile.exists() && nestedFile.length() > 0) {
                                    CoverExtractor.extract(nestedFile)
                                } else {
                                    null
                                }
                            } else {
                                CoverExtractor.extract(app, book.documentUri)
                            }
                            if (extracted != null) {
                                CoverCache.saveBitmap(file, extracted)
                                coverMetaRepo.save(bookId, CoverStatus.OK)
                            } else if (book.nestedEntryName == null) {
                                // nested 책은 캐시가 없을 뿐이지 깨진 게 아니므로 FAILED 영구 저장 안 함.
                                coverMetaRepo.save(bookId, CoverStatus.FAILED)
                            }
                            extracted
                        }
                    }
                }?.asImageBitmap()
                // nested 책이 추출 캐시 없어 image=null로 끝난 케이스는 status를 FAILED로 박지 않음
                // — 사용자가 reader에서 한 번 열어 캐시가 생기면 다음 라이브러리 진입에서 다시 추출 가능.
                val newStatus = when {
                    image != null -> CoverStatus.OK
                    book.nestedEntryName != null -> null  // skip
                    else -> CoverStatus.FAILED
                }
                if (image != null || newStatus != null) {
                    queueCoverUpdate(bookId, image, newStatus ?: CoverStatus.FAILED)
                }
            } finally {
                // cancel/예외 케이스에도 dedup map 정리 — 다음 요청에서 재시도 가능.
                coverJobs.remove(bookId)
            }
        }
        coverJobs[bookId] = job
    }

    private fun refreshRoots() {
        listJob?.cancel()
        listJob = viewModelScope.launch {
            _state.value = _state.value.copy(scanning = true)
            val rootEntries = repo.listRoots(_state.value.roots)

            // 자동 진입: root 1개면 한 프레임도 root 화면을 노출하지 않고
            // 바로 그 안 children 로드 — e-ink 깜빡임 방지.
            if (pendingAutoDescend && rootEntries.size == 1 && _state.value.path.isEmpty()) {
                pendingAutoDescend = false
                val target = rootEntries.first()
                setPath(listOf(target))
                val children = repo.listChildren(target)
                val sorted = applySort(children, _state.value.sortMode)
                _state.value = _state.value.copy(entries = sorted, scanning = false)
                prefetchBookData(sorted)
            } else {
                pendingAutoDescend = false
                // root 자체는 폴더라 sortMode와 무관하게 이름순(repo가 이미 정렬).
                _state.value = _state.value.copy(entries = rootEntries, scanning = false)
            }
        }
    }

    private fun refreshChildren(folder: FolderEntry) {
        listJob?.cancel()
        listJob = viewModelScope.launch {
            _state.value = _state.value.copy(scanning = true)
            val children = repo.listChildren(folder)
            val sorted = applySort(children, _state.value.sortMode)
            _state.value = _state.value.copy(entries = sorted, scanning = false)
            prefetchBookData(sorted)
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
        status: io.github.sejoung.panelyink.data.db.CoverStatus,
    ) {
        if (image != null) pendingCovers[bookId] = image
        pendingCoverStatus[bookId] = status
        if (coverFlushJob?.isActive == true) return
        coverFlushJob = viewModelScope.launch {
            delay(COVER_FLUSH_DEBOUNCE_MS)
            flushCoverUpdates()
        }
    }

    private fun flushCoverUpdates() {
        if (pendingCovers.isEmpty() && pendingCoverStatus.isEmpty()) return
        val covers = pendingCovers.toMap()
        val status = pendingCoverStatus.toMap()
        pendingCovers.clear()
        pendingCoverStatus.clear()
        _state.value = _state.value.copy(
            covers = if (covers.isNotEmpty()) _state.value.covers + covers else _state.value.covers,
            coverStatus = if (status.isNotEmpty()) _state.value.coverStatus + status else _state.value.coverStatus,
        )
    }

    /**
     * 폴더 진입 시 책들의 진행률 + 표지 메타를 batch로 한 번에 채움 — 책 N권 화면이면
     * 이전엔 (N progress query) + (N cover meta query) = 2N개. 이제 2 query.
     * BookRow의 [requestProgress]/[requestCover]는 캐시 hit으로 추가 query 없음.
     */
    private suspend fun prefetchBookData(entries: List<LibraryEntry>) {
        val books = entries.filterIsInstance<BookEntry>()
        if (books.isEmpty()) return
        val bookIds = books.map { PositionKey.bookIdFromUri(it.bookIdSource) }
        // 두 batch query 동시 실행 — async/await로 더 줄일 수 있지만 IO 디스패처 한계라
        // 큰 차이 X. 순차 호출로 단순 유지.
        val progressMap = positionRepo.loadProgressMap(bookIds)
        val coverMetaMap = coverMetaRepo.loadMap(bookIds)

        val knownProgress = progressMap.filterValues { it.isKnown }
        val statusMap = coverMetaMap.mapValues { it.value.status }
        if (knownProgress.isNotEmpty() || statusMap.isNotEmpty()) {
            _state.value = _state.value.copy(
                bookProgress = _state.value.bookProgress + knownProgress,
                coverStatus = _state.value.coverStatus + statusMap,
            )
        }
    }

    /**
     * 정렬 모드 적용 — 폴더는 항상 이름순 고정, 책만 모드에 따라.
     *
     * - [SortMode.Name]: repo가 이미 자연순으로 반환했으므로 그대로
     * - [SortMode.LastOpened]: position 테이블 batch 쿼리로 updated_at 가져와 내림차순.
     *   미열람 책(맵에 없음)은 0 취급되어 맨 뒤로 가고, 그 안에서는 자연순 유지.
     */
    private suspend fun applySort(
        entries: List<LibraryEntry>,
        mode: SortMode,
    ): List<LibraryEntry> {
        val folders = entries.filterIsInstance<FolderEntry>()
        val books = entries.filterIsInstance<BookEntry>()
        val sortedBooks = when (mode) {
            SortMode.Name -> books // repo가 이미 자연순
            SortMode.LastOpened -> {
                val ids = books.map { PositionKey.bookIdFromUri(it.bookIdSource) }
                val timestamps = positionRepo.loadUpdatedAtMap(ids)
                books.sortedWith(
                    compareByDescending<BookEntry> {
                        timestamps[PositionKey.bookIdFromUri(it.bookIdSource)] ?: 0L
                    }.thenBy(NaturalOrderComparator) { it.displayName },
                )
            }
        }
        return folders + sortedBooks
    }

    /** path 변경의 단일 진입점. state 갱신 + 디스크 영속화를 한 곳에서 묶어 누락을 방지. */
    private fun setPath(newPath: List<FolderEntry>, alsoUpdateRoots: List<Uri>? = null) {
        _state.value = if (alsoUpdateRoots != null) {
            _state.value.copy(roots = alsoUpdateRoots, path = newPath)
        } else {
            _state.value.copy(path = newPath)
        }
        prefs.edit { putString(KEY_PATH, encodePath(newPath)) }
    }

    private fun persistRoots(roots: List<Uri>) {
        prefs.edit { putStringSet(KEY_ROOTS, roots.map(Uri::toString).toSet()) }
    }

    /**
     * 저장된 path가 현재 roots와 정합한지 검증 — root가 사라졌거나 path 첫 단계의
     * rootUri가 더 이상 추가된 root 목록에 없으면 path는 무효.
     *
     * 폴더 자체 존재 여부(SAF에서 사라진 폴더)는 여기서 검증하지 않는다 — refreshChildren이
     * 빈 결과를 반환하면 사용자가 ← back으로 한 단계 위로 가면 됨.
     */
    private fun isPathValid(path: List<FolderEntry>, currentRoots: List<Uri>): Boolean {
        if (path.isEmpty()) return false
        val firstRoot = path.first().rootUri
        return firstRoot in currentRoots && path.all { it.rootUri == firstRoot }
    }

    private fun encodePath(path: List<FolderEntry>): String {
        val arr = JSONArray()
        for (f in path) {
            arr.put(JSONObject().apply {
                put(JSON_NAME, f.displayName)
                put(JSON_DOC, f.documentUri.toString())
                put(JSON_ROOT, f.rootUri.toString())
                put(JSON_IS_ROOT, f.isRoot)
            })
        }
        return arr.toString()
    }

    private fun decodePath(raw: String?): List<FolderEntry> {
        if (raw.isNullOrEmpty()) return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                FolderEntry(
                    documentUri = Uri.parse(o.getString(JSON_DOC)),
                    displayName = o.getString(JSON_NAME),
                    rootUri = Uri.parse(o.getString(JSON_ROOT)),
                    isRoot = o.getBoolean(JSON_IS_ROOT),
                )
            }
        }.getOrElse { emptyList() }
    }

    companion object {
        private const val COVER_FLUSH_DEBOUNCE_MS = 100L
        private const val PREFS_NAME = "panely_ink_prefs"
        private const val KEY_ROOTS = "library_root_uris"
        private const val KEY_PATH = "library_last_path"
        private const val KEY_SORT = "library_sort_mode"
        private const val KEY_VIEW = "library_view_mode"
        private const val JSON_NAME = "name"
        private const val JSON_DOC = "doc"
        private const val JSON_ROOT = "root"
        private const val JSON_IS_ROOT = "isRoot"
    }
}

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
    /** bookId → 책 진행률(현재 페이지 + 전체). 미열람/모름 책은 키 없음. */
    val bookProgress: Map<String, BookProgress> = emptyMap(),
    /**
     * bookId → CoverMeta 상태 캐시. `prefetchBookData`에서 폴더 진입 시 batch로 한 번에
     * 채움 → `requestCover`는 캐시 우선 확인해 책당 추가 Room query 안 일어남.
     * 키 없음 = 메타 미존재(첫 추출 필요).
     */
    val coverStatus: Map<String, io.github.sejoung.panelyink.data.db.CoverStatus> = emptyMap(),
    /** 라이브러리 정렬 모드. 영속됨. */
    val sortMode: SortMode = SortMode.DEFAULT,
    /** 라이브러리 표시 모드(List/Cover/Grid). 영속됨. */
    val viewMode: ViewMode = ViewMode.DEFAULT,
    /** 현재 폴더 in-memory 검색어. 빈 문자열이면 필터 적용 X. 폴더 이동 시 자동 클리어. */
    val searchQuery: String = "",
)
