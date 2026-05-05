package io.github.sejoung.panelyink.library

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 라이브러리 화면용 상태 보유.
 *
 * - SAF 트리 URI들을 영속 권한과 함께 보관 (재실행 후에도 같은 폴더에 접근 가능)
 * - 추가/제거 시 디스크 prefs와 SAF 권한 양쪽을 갱신
 * - 자동 스캔 1회. 수동 새로고침은 M3에서.
 */
class LibraryViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = application.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
    private val repo = LibraryRepository(application)

    private val _state = MutableStateFlow(LibraryState())
    val state: StateFlow<LibraryState> = _state.asStateFlow()

    init {
        val saved = prefs.getStringSet(KEY_ROOTS, emptySet()).orEmpty().map(Uri::parse)
        _state.value = _state.value.copy(roots = saved)
        rescan()
    }

    fun addRoot(uri: Uri) {
        // takePersistableUriPermission은 read/write 플래그만 받는다.
        // "persistable" 의미는 메서드 이름 자체가 담당.
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
        rescan()
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
        _state.value = _state.value.copy(roots = next)
        rescan()
    }

    private fun persist(roots: List<Uri>) {
        prefs.edit { putStringSet(KEY_ROOTS, roots.map(Uri::toString).toSet()) }
    }

    private fun rescan() {
        viewModelScope.launch {
            _state.value = _state.value.copy(scanning = true)
            val entries = repo.scan(_state.value.roots)
            _state.value = _state.value.copy(entries = entries, scanning = false)
        }
    }

    companion object {
        private const val PREFS_NAME = "panely_ink_prefs"
        private const val KEY_ROOTS = "library_root_uris"
    }
}

/** 라이브러리 화면이 관찰하는 불변 상태. */
data class LibraryState(
    val roots: List<Uri> = emptyList(),
    val entries: List<LibraryEntry> = emptyList(),
    val scanning: Boolean = false,
)
