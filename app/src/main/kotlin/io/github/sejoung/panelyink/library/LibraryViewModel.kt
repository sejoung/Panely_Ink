package io.github.sejoung.panelyink.library

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 라이브러리 화면용 상태 보유. 트리 탐색 모델:
 *
 * - [LibraryState.path] 가 비어있으면 root 목록(첫 화면)
 * - 비어있지 않으면 마지막 항목이 "현재 폴더", entries는 그 폴더의 직계
 * - root 추가/제거 시 path는 root까지 비움 (사용자 컨텍스트가 깨지지 않게)
 *
 * SAF 권한은 root URI에만 부여 — 모든 자식 접근은 같은 권한 트리 안에서 일어난다.
 */
class LibraryViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = application.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
    private val repo = LibraryRepository(application)

    private val _state = MutableStateFlow(LibraryState())
    val state: StateFlow<LibraryState> = _state.asStateFlow()

    /** 현재 진행 중인 list/scan 작업. 폴더 진입 도중 새 진입이 들어오면 cancel. */
    private var listJob: Job? = null

    /**
     * 다음 [refreshRoots] 시 자동으로 단일 root 안으로 한 단계 진입할지.
     *
     * - init / addRoot / removeRoot 직후에 켠다(사용자가 추가/제거한 결과 root가 1개로
     *   정리된 시점). root 1개면 첫 화면 = "폴더 1개" 짜리 의미 없는 단계라 건너뜀.
     * - [goUp]으로 사용자가 의도적으로 root 화면에 도달했을 땐 끈다 — 무한 루프 방지.
     */
    private var pendingAutoDescend = false

    init {
        val saved = prefs.getStringSet(KEY_ROOTS, emptySet()).orEmpty().map(Uri::parse)
        _state.value = _state.value.copy(roots = saved)
        pendingAutoDescend = true
        refreshRoots()
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
        persist(next)
        _state.value = _state.value.copy(roots = next)
        // 새 root 1개로 시작했으면 자동 진입. 이미 다른 root에 들어와 있다면 그대로 둔다.
        if (next.size == 1 && _state.value.path.isEmpty()) {
            pendingAutoDescend = true
            refreshRoots()
        } else if (_state.value.path.isEmpty()) {
            refreshRoots()
        }
        // 폴더 안에 들어가 있는 동안의 추가는 entries에 영향 없음(현재 폴더 children은 그대로).
    }

    fun removeRoot(uri: Uri) {
        runCatching {
            getApplication<Application>().contentResolver.releasePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
        val next = _state.value.roots.filterNot { it == uri }
        persist(next)
        // 사용자가 현재 진입해 있던 root가 사라졌으면 첫 화면으로 복귀.
        val newPath = _state.value.path.takeIf { it.firstOrNull()?.rootUri != uri }.orEmpty()
        _state.value = _state.value.copy(roots = next, path = newPath)
        if (newPath.isEmpty()) {
            // 제거 결과 root 1개만 남았으면 거기로 자동 진입.
            pendingAutoDescend = (next.size == 1)
            refreshRoots()
        } else {
            refreshChildren(newPath.last())
        }
    }

    /** 폴더 진입 — path에 push 후 그 폴더의 직계 로드. */
    fun enterFolder(folder: FolderEntry) {
        val nextPath = _state.value.path + folder
        _state.value = _state.value.copy(path = nextPath)
        refreshChildren(folder)
    }

    /**
     * 한 단계 위로. 이미 root 목록이면 false 반환(상위가 없음 — caller가 시스템 뒤로 처리).
     */
    fun goUp(): Boolean {
        val current = _state.value.path
        if (current.isEmpty()) return false
        val nextPath = current.dropLast(1)
        _state.value = _state.value.copy(path = nextPath)
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
                _state.value = _state.value.copy(path = listOf(target))
                val children = repo.listChildren(target)
                _state.value = _state.value.copy(entries = children, scanning = false)
            } else {
                pendingAutoDescend = false
                _state.value = _state.value.copy(entries = rootEntries, scanning = false)
            }
        }
    }

    private fun refreshChildren(folder: FolderEntry) {
        listJob?.cancel()
        listJob = viewModelScope.launch {
            _state.value = _state.value.copy(scanning = true)
            val children = repo.listChildren(folder)
            _state.value = _state.value.copy(entries = children, scanning = false)
        }
    }

    private fun persist(roots: List<Uri>) {
        prefs.edit { putStringSet(KEY_ROOTS, roots.map(Uri::toString).toSet()) }
    }

    companion object {
        private const val PREFS_NAME = "panely_ink_prefs"
        private const val KEY_ROOTS = "library_root_uris"
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
)
