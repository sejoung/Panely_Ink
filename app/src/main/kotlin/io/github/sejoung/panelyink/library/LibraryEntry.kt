package io.github.sejoung.panelyink.library

import android.net.Uri

/**
 * 라이브러리 화면 한 행. 트리 탐색 모델로 두 종류:
 * - [BookEntry]   — 사용자가 열어 읽을 한 권 (.cbz / .zip)
 * - [FolderEntry] — 진입하면 그 안의 직계 자식들이 보이는 폴더
 *
 * Root 폴더(사용자가 SAF로 추가한 최상위)도 [FolderEntry]로 표현된다 — 첫 화면에선
 * root들이 폴더 행으로 나열되어 보인다(`isRoot = true`).
 */
sealed interface LibraryEntry {
    val documentUri: Uri
    val displayName: String
    val rootUri: Uri
}

/** 책 1권. ReaderScreen에 그대로 전달돼 [io.github.sejoung.panelyink.reader.CbzBookSession]가 연다. */
data class BookEntry(
    override val documentUri: Uri,
    override val displayName: String,
    val sizeBytes: Long,
    val mimeType: String?,
    override val rootUri: Uri,
) : LibraryEntry

/**
 * 폴더 1개. [isRoot]이면 사용자가 직접 추가한 SAF 트리(루트), 아니면 그 안의 하위 폴더.
 * 자식 카운트는 1단계에선 표시하지 않는다 — 향후 lazy 백그라운드 캐시로 보강(M3).
 */
data class FolderEntry(
    override val documentUri: Uri,
    override val displayName: String,
    override val rootUri: Uri,
    val isRoot: Boolean,
) : LibraryEntry
