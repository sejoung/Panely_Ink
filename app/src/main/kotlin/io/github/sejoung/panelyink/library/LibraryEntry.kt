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

/**
 * 책 1권. ReaderScreen에 그대로 전달돼 [io.github.sejoung.panelyink.reader.CbzBookSession]가 연다.
 *
 * [nestedEntryName]이 null이 아니면 ZIP-of-CBZ의 자식 책 — [documentUri]는 부모 ZIP의
 * SAF URI이고, 진입 시 nested entry를 추출해 reader에 전달.
 */
data class BookEntry(
    override val documentUri: Uri,
    override val displayName: String,
    val sizeBytes: Long,
    val mimeType: String?,
    override val rootUri: Uri,
    /** ZIP-of-CBZ의 자식이면 부모 ZIP 안 entry name. null이면 SAF 직속 책. */
    val nestedEntryName: String? = null,
) : LibraryEntry

/**
 * 폴더 1개. [isRoot]이면 사용자가 직접 추가한 SAF 트리(루트), 아니면 그 안의 하위 폴더.
 *
 * [nestedBooks]가 null이 아니면 ZIP-of-CBZ를 가상 폴더로 표시하는 케이스 — [documentUri]는
 * 부모 ZIP의 SAF URI이고 listChildren은 SAF query 대신 [nestedBooks]를 그대로 반환.
 */
data class FolderEntry(
    override val documentUri: Uri,
    override val displayName: String,
    override val rootUri: Uri,
    val isRoot: Boolean,
    /** ZIP-of-CBZ의 가상 폴더이면 자식 책 목록. SAF 폴더면 null. */
    val nestedBooks: List<BookEntry>? = null,
) : LibraryEntry
