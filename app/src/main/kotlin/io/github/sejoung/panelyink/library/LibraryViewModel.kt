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
import io.github.sejoung.panelyink.data.db.PanelyDatabase
import io.github.sejoung.panelyink.data.db.RoomPositionRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
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
    private val positionRepo = RoomPositionRepository(PanelyDatabase.getInstance(application))

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
        _state.value = _state.value.copy(
            roots = savedRoots,
            path = savedPath,
            sortMode = savedSort,
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

    /** 폴더 진입 — path에 push 후 그 폴더의 직계 로드. */
    fun enterFolder(folder: FolderEntry) {
        val nextPath = _state.value.path + folder
        setPath(nextPath)
        refreshChildren(folder)
    }

    /**
     * 한 단계 위로. 이미 root 목록이면 false 반환(상위가 없음 — caller가 시스템 뒤로 처리).
     */
    fun goUp(): Boolean {
        val current = _state.value.path
        if (current.isEmpty()) return false
        val nextPath = current.dropLast(1)
        setPath(nextPath)
        if (nextPath.isEmpty()) {
            // 사용자 의도로 root 목록까지 올라온 경우 — 자동 진입 X
            pendingAutoDescend = false
            refreshRoots()
        } else {
            refreshChildren(nextPath.last())
        }
        return true
    }

    /** 명시적 새로고침 — 같은 위치 다시 스캔. */
    fun refresh() {
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
            val count = repo.countBooks(folder)
            _state.value = _state.value.copy(
                folderCounts = _state.value.folderCounts + (key to count),
            )
            countingJobs.remove(key)
        }
        countingJobs[key] = job
    }

    /**
     * 책 행이 화면에 등장할 때 호출 — Room에서 진행률을 lazy 로드해 캐시.
     *
     * 미열람 책은 Room에 row 자체가 없어 결과 null → covers와 달리 map에 키 자체를
     * 추가하지 않는다(라이브러리 라벨 표시 X). 사용자가 한 번이라도 책을 열면 다음
     * 라이브러리 진입에서 % 라벨이 등장.
     */
    fun requestProgress(book: BookEntry) {
        val bookId = PositionKey.bookIdFromUri(book.documentUri.toString())
        if (_state.value.bookProgress.containsKey(bookId)) return
        if (progressJobs.containsKey(bookId)) return
        val job = viewModelScope.launch {
            val progress = positionRepo.load(bookId)
            if (progress != null && progress.isKnown) {
                _state.value = _state.value.copy(
                    bookProgress = _state.value.bookProgress + (bookId to progress),
                )
            }
            progressJobs.remove(bookId)
        }
        progressJobs[bookId] = job
    }

    /**
     * 책 행이 화면에 등장할 때 호출 — 표지를 lazy 추출해 캐시에 적재.
     *
     * 동작:
     * 1. in-memory hit이면 즉시 반환
     * 2. 디스크 hit이면 비트맵 디코드 → in-memory 추가
     * 3. 둘 다 miss면 [CoverExtractor]로 첫 페이지 디코드 → 디스크 저장 → in-memory 추가
     *
     * 추출은 archive open 비용(~수백 ms)이 있어 표지 1장씩 IO에서 순차로 처리하지만,
     * 동시 여러 책 요청은 코루틴 dispatcher에 맡긴다 — viewModelScope는 Default 디스패처
     * 다중 처리. 화면 밖으로 나간 책 작업도 끝까지 진행해 다음 진입에서 즉시 hit.
     */
    fun requestCover(book: BookEntry) {
        val bookId = PositionKey.bookIdFromUri(book.documentUri.toString())
        if (_state.value.covers.containsKey(bookId)) return
        if (coverJobs.containsKey(bookId)) return
        val app = getApplication<Application>()
        val job = viewModelScope.launch {
            val image = withContext(Dispatchers.IO) {
                val file = CoverCache.cacheFile(app, bookId)
                CoverCache.loadBitmap(file)
                    ?: CoverExtractor.extract(app, book.documentUri)?.also { extracted ->
                        CoverCache.saveBitmap(file, extracted)
                    }
            }?.asImageBitmap()
            if (image != null) {
                _state.value = _state.value.copy(
                    covers = _state.value.covers + (bookId to image),
                )
            }
            coverJobs.remove(bookId)
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
                val ids = books.map { PositionKey.bookIdFromUri(it.documentUri.toString()) }
                val timestamps = positionRepo.loadUpdatedAtMap(ids)
                books.sortedWith(
                    compareByDescending<BookEntry> {
                        timestamps[PositionKey.bookIdFromUri(it.documentUri.toString())] ?: 0L
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
        private const val PREFS_NAME = "panely_ink_prefs"
        private const val KEY_ROOTS = "library_root_uris"
        private const val KEY_PATH = "library_last_path"
        private const val KEY_SORT = "library_sort_mode"
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
    /** bookId → 책 진행률(현재 페이지 + 전체). 미열람/모름 책은 키 없음. */
    val bookProgress: Map<String, BookProgress> = emptyMap(),
    /** 라이브러리 정렬 모드. 영속됨. */
    val sortMode: SortMode = SortMode.DEFAULT,
)
