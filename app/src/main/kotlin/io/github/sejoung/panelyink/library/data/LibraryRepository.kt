package io.github.sejoung.panelyink.library.data

import io.github.sejoung.panelyink.library.model.BookEntry
import io.github.sejoung.panelyink.library.model.FolderEntry
import io.github.sejoung.panelyink.library.model.LibraryEntry
import io.github.sejoung.panelyink.library.model.SortMode
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import io.github.sejoung.panelyink.core.archive.CbzArchive
import io.github.sejoung.panelyink.core.sort.NaturalOrderComparator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * SAF 트리 URI 위에서 폴더 1단계만 열거한다 — 사용자가 한 화면씩 탐색하는
 * 모델(7" e-reader에 맞춤). PRD §6.1: "depth-1은 즉시", 더 깊은 스캔은
 * 사용자가 폴더에 진입한 시점에 그 폴더에 대해 새로 한 단계 [listChildren].
 *
 * **성능 메모(2026-05-09)**: `androidx.documentfile.DocumentFile.listFiles()`는 첫 IPC
 * 1번 후 각 자식의 `length()`/`type()`/`name` 호출마다 추가 ContentResolver query를
 * 또 일으킨다(N+1 IPC). 폴더에 책 50권이면 51번 IPC, `countBooks`는 재귀로 폭주.
 * 이 클래스는 `ContentResolver.query` + `DocumentsContract.buildChildDocumentsUriUsingTree`
 * 로 자식의 모든 컬럼(name/mime/size)을 한 번의 쿼리에 받아 폴더당 IPC를 1번으로 줄인다.
 *
 * v1.0 1단계 스코프:
 * - root → root 자신을 [FolderEntry]로 변환 ([listRoots])
 * - 폴더 진입 → 직계 자식만 ([listChildren]). 폴더 + 책 모두 반환
 * - [countBooks] 재귀(PRD §6.1 "최대 3단계")
 * - [firstBookIn] 시리즈 그룹핑용 첫 책
 *
 * 비스코프(다음 단계):
 * - ZIP 안의 다권 펼침 (`vol1.cbz vol2.cbz` in `series.zip`) — PRD §6.1 중첩 아카이브
 * - CBR(.rar) — v1.5 (junrar GPL 격리 비용)
 */
class LibraryRepository(private val context: Context) {

    private val resolver get() = context.contentResolver

    /**
     * 폴더 documentUri → listChildren 결과 in-memory 캐시. 폴더 트리 위/아래 이동 시
     * 같은 폴더 재진입에서 SAF query 0번. 외장 SD card에서 큰 효과.
     *
     * 무효화 정책:
     * - [invalidateCache]: 사용자 명시 새로고침 시 호출
     * - 프로세스 살아있는 동안 자동 invalidate X — 외부에서 파일 변경되면 stale 가능
     *   하지만 라이브러리는 일반적으로 정적이라 사용 패턴상 OK
     *
     * 동기화: 호출자는 Main에서 시작하지만 실제 접근은 Dispatchers.IO로 넘어가므로 짧은 lock으로 보호.
     */
    private val childrenCache = mutableMapOf<Uri, List<LibraryEntry>>()

    /**
     * zip의 `documentUri` → ZIP-of-CBZ 가상 폴더 변환 결과 캐시. 시리즈가 아닌 zip은 null로
     * 명시 캐시(`containsKey`로 hit/miss 구분). 한 번 ZIP을 열어 검사하면 프로세스 살아있는
     * 동안 재검사 없이 즉시 응답 → 라이브러리 폴더 위/아래 이동에서 매번 ZIP을 다시 안 연다.
     */
    private val seriesCache = mutableMapOf<Uri, FolderEntry?>()
    private val cacheLock = Any()

    /** 사용자 명시 새로고침 시 캐시 비우기. */
    fun invalidateCache() {
        synchronized(cacheLock) {
            childrenCache.clear()
            seriesCache.clear()
        }
    }

    /** 사용자가 추가한 SAF 트리 [Uri]들을 첫 화면용 root 폴더 행으로 변환. */
    suspend fun listRoots(rootUris: List<Uri>): List<FolderEntry> = withContext(Dispatchers.IO) {
        rootUris.mapNotNull { uri ->
            val name = queryRootName(uri) ?: uri.lastPathSegment ?: uri.toString()
            FolderEntry(
                documentUri = uri, // tree URI 그대로 — isRoot=true 분기로 식별
                displayName = name,
                rootUri = uri,
                isRoot = true,
            )
        }.sortedWith(compareBy(NaturalOrderComparator) { it.displayName })
    }

    /**
     * [parent] 폴더의 직계 자식 — 폴더 + 책. 폴더 먼저, 책 나중. 각각 자연 정렬.
     * AppleDouble / 닷파일은 제외.
     *
     * 1번의 [ContentResolver.query]로 모든 자식의 메타(name/mime/size)를 한 번에 받아
     * `DocumentFile.listFiles + length()` 패턴의 N+1 IPC를 제거.
     */
    suspend fun listChildren(parent: FolderEntry): List<LibraryEntry> =
        withContext(Dispatchers.IO) {
            // 가상 폴더(ZIP-of-CBZ) — SAF query 없이 인스턴스가 들고 있는 nested 목록
            // 그대로. 정렬은 inspectZipForSeries가 이미 자연순으로 만들어놨다.
            parent.nestedBooks?.let { return@withContext it }
            // in-memory 캐시 hit → SAF query 0번
            synchronized(cacheLock) {
                childrenCache[parent.documentUri]
            }?.let { return@withContext it }
            val parentDocId = parent.documentIdForChildren()
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
                parent.rootUri, parentDocId,
            )
            val results = mutableListOf<LibraryEntry>()
            resolver.query(childrenUri, CHILD_PROJECTION, null, null, null)?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
                val sizeCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_SIZE)
                while (cursor.moveToNext()) {
                    val name = cursor.getString(nameCol) ?: continue
                    // macOS AppleDouble (._book.cbz) / 닷파일(.DS_Store) 제외 — FAT/exFAT/SMB로
                    // 옮길 때 Finder가 짝으로 만들어 두는 메타데이터.
                    if (name.startsWith(".")) continue
                    val docId = cursor.getString(idCol)
                    val mime = cursor.getString(mimeCol)
                    val childUri = DocumentsContract.buildDocumentUriUsingTree(
                        parent.rootUri, docId,
                    )
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                        results += FolderEntry(
                            documentUri = childUri,
                            displayName = name,
                            rootUri = parent.rootUri,
                            isRoot = false,
                        )
                    } else if (isCbzOrZipName(name)) {
                        val size = if (cursor.isNull(sizeCol)) 0L else cursor.getLong(sizeCol)
                        results += BookEntry(
                            documentUri = childUri,
                            displayName = name,
                            sizeBytes = size,
                            mimeType = mime,
                            rootUri = parent.rootUri,
                        )
                    }
                }
            }
            val folders = results.filterIsInstance<FolderEntry>()
                .sortedWith(compareBy(NaturalOrderComparator) { it.displayName })
            val books = results.filterIsInstance<BookEntry>()
                .sortedWith(compareBy(NaturalOrderComparator) { it.displayName })
            val sorted = folders + books
            synchronized(cacheLock) {
                childrenCache[parent.documentUri] = sorted
            }
            sorted
        }

    /**
     * [folder] 안 첫 책(자연 정렬) — 시리즈 그룹핑(폴더=시리즈)에서 폴더 행에 첫 권의
     * 표지를 보여주기 위해 사용. depth 1만 본다. 폴더가 비었거나 폴더만 있으면 null.
     */
    suspend fun firstBookIn(folder: FolderEntry): BookEntry? = withContext(Dispatchers.IO) {
        listChildren(folder).firstOrNull { it is BookEntry } as? BookEntry
    }

    /**
     * [book]이 ZIP-of-CBZ(중첩 아카이브 시리즈)이면 가상 폴더로 변환해 반환. 아니면 null.
     *
     * 호출 시점: 사용자가 책 행 클릭 직후. ZIP을 열어 entries 분류 → 이미지가 없고
     * nested .cbz/.zip이 2+이면 [FolderEntry.nestedBooks]로 자식 책 변환.
     *
     * 비용: 부모 ZIP 한 번 열기(Commons Compress + setIgnoreLocalFileHeader). 외장 SD에서
     * 보통 100~500ms. 결과는 호출자가 캐싱.
     */
    suspend fun inspectZipForSeries(book: BookEntry): FolderEntry? = withContext(Dispatchers.IO) {
        // 이미 nested entry인 책은 ZIP-of-CBZ 자식이라 다시 검사 안 함.
        if (book.nestedEntryName != null) return@withContext null
        synchronized(cacheLock) {
            if (seriesCache.containsKey(book.documentUri)) {
                return@withContext seriesCache[book.documentUri]
            }
        }
        val archive = runCatching { CbzArchive.open(context, book.documentUri) }.getOrElse {
            Log.w("PanelyInk.Library", "inspect open failed: ${book.documentUri}", it)
            synchronized(cacheLock) {
                seriesCache[book.documentUri] = null
            }
            return@withContext null
        }
        val result = try {
            if (!archive.isSeriesArchive) {
                null
            } else {
                val nestedBooks = archive.nestedArchives.map { entry ->
                    BookEntry(
                        documentUri = book.documentUri,
                        displayName = entry.displayName,
                        sizeBytes = entry.size,
                        mimeType = null,
                        rootUri = book.rootUri,
                        nestedEntryName = entry.entryName,
                    )
                }
                FolderEntry(
                    documentUri = book.documentUri,
                    displayName = book.displayName,
                    rootUri = book.rootUri,
                    isRoot = false,
                    nestedBooks = nestedBooks,
                )
            }
        } finally {
            archive.close()
        }
        synchronized(cacheLock) {
            seriesCache[book.documentUri] = result
        }
        result
    }

    /**
     * [folder] 안의 책(.cbz/.zip) 갯수를 재귀적으로 센다 — 폴더 행에 "N권"을
     * 미리 보여주기 위함. PRD §6.1 "중첩 아카이브 추출 최대 3단계" 그대로 [maxDepth] 제한.
     *
     * [listChildren] 결과를 활용 → in-memory 캐시 hit 시 SAF query 0번. 폴더 트리
     * 위/아래 이동에서 큰 효과(외장 SD card).
     */
    suspend fun countBooks(folder: FolderEntry, maxDepth: Int = 3): Int =
        withContext(Dispatchers.IO) { countBooksRec(folder, depth = 0, maxDepth) }

    private suspend fun countBooksRec(
        folder: FolderEntry,
        depth: Int,
        maxDepth: Int,
    ): Int {
        if (depth > maxDepth) return 0
        val children = listChildren(folder)
        val bookCount = children.count { it is BookEntry }
        var subCount = 0
        for (child in children) {
            if (child is FolderEntry) {
                subCount += countBooksRec(child, depth + 1, maxDepth)
            }
        }
        return bookCount + subCount
    }

    /** root는 tree URI(getTreeDocumentId), 자식은 일반 document URI(getDocumentId). */
    private fun FolderEntry.documentIdForChildren(): String =
        if (isRoot || documentUri == rootUri) {
            DocumentsContract.getTreeDocumentId(rootUri)
        } else {
            DocumentsContract.getDocumentId(documentUri)
        }

    private fun queryRootName(treeUri: Uri): String? {
        val docId = DocumentsContract.getTreeDocumentId(treeUri)
        val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
        return resolver.query(
            docUri,
            arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null, null, null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }

    private fun isCbzOrZipName(name: String): Boolean {
        val ext = name.substringAfterLast('.', missingDelimiterValue = "").lowercase()
        return ext == "cbz" || ext == "zip"
    }

    companion object {
        private val CHILD_PROJECTION = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
        )
    }
}
