package io.github.sejoung.panelyink.library

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import io.github.sejoung.panelyink.core.sort.NaturalOrderComparator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * SAF 트리 URI 위에서 폴더 1단계만 열거한다 — 사용자가 한 화면씩 탐색하는
 * 모델(7" e-reader에 맞춤). PRD §6.1: "depth-1은 즉시", 더 깊은 스캔은
 * 사용자가 폴더에 진입한 시점에 그 폴더에 대해 새로 한 단계 [listChildren].
 *
 * v1.0 1단계 스코프:
 * - root → root 자신을 [FolderEntry]로 변환 ([listRoots])
 * - 폴더 진입 → 직계 자식만 ([listChildren]). 폴더 + 책 모두 반환
 * - 자식 카운트는 표시하지 않음 (M3에서 lazy 캐시로 추가 예정)
 *
 * 비스코프(다음 단계):
 * - ZIP 안의 다권 펼침 (`vol1.cbz vol2.cbz` in `series.zip`) — PRD §6.1 중첩 아카이브
 * - CBR(.rar) — v1.5 (junrar GPL 격리 비용)
 * - 표지/진행률/시리즈 그룹핑 — M3
 */
class LibraryRepository(private val context: Context) {

    /** 사용자가 추가한 SAF 트리 [Uri]들을 첫 화면용 root 폴더 행으로 변환. */
    suspend fun listRoots(rootUris: List<Uri>): List<FolderEntry> = withContext(Dispatchers.IO) {
        rootUris.mapNotNull { uri ->
            val doc = DocumentFile.fromTreeUri(context, uri) ?: return@mapNotNull null
            FolderEntry(
                documentUri = doc.uri,
                displayName = doc.name ?: uri.lastPathSegment ?: uri.toString(),
                rootUri = uri,
                isRoot = true,
            )
        }.sortedWith(compareBy(NaturalOrderComparator) { it.displayName })
    }

    /**
     * [parent] 폴더의 직계 자식만 반환 — 폴더는 [FolderEntry], CBZ/ZIP은 [BookEntry].
     * AppleDouble / 닷파일은 제외.
     */
    suspend fun listChildren(parent: FolderEntry): List<LibraryEntry> = withContext(Dispatchers.IO) {
        val parentDoc = resolveFolder(parent) ?: return@withContext emptyList()
        val (folders, books) = parentDoc.listFiles().asSequence()
            .filter { df -> df.isAcceptable() }
            .partition { it.isDirectory }

        val folderEntries = folders.map { df ->
            FolderEntry(
                documentUri = df.uri,
                displayName = df.name ?: "(unknown)",
                rootUri = parent.rootUri,
                isRoot = false,
            )
        }.sortedWith(compareBy(NaturalOrderComparator) { it.displayName })

        val bookEntries = books.asSequence()
            .filter { it.isCbzOrZip() }
            .map { df ->
                BookEntry(
                    documentUri = df.uri,
                    displayName = df.name ?: "(unknown)",
                    sizeBytes = df.length(),
                    mimeType = df.type,
                    rootUri = parent.rootUri,
                )
            }
            .sortedWith(compareBy(NaturalOrderComparator) { it.displayName })
            .toList()

        // 폴더 먼저, 책 나중 — Finder/Explorer 식 자연스러운 정렬
        folderEntries + bookEntries
    }

    /**
     * [folder]가 root이면 트리 root를 그대로, 그 외엔 root → 자식 chain을 따라 내려간다.
     *
     * SAF 제약: `DocumentFile.fromTreeUri`는 사용자가 권한 부여한 root URI에만 동작한다.
     * 자식의 documentUri를 다시 fromTreeUri에 넘기면 ContentResolver 권한 검증에서 실패하는
     * 디바이스가 있어, 항상 root에서 listFiles로 한 단계씩 내려가서 동일 [Uri]를 매칭한다.
     */
    private fun resolveFolder(folder: FolderEntry): DocumentFile? {
        val rootDoc = DocumentFile.fromTreeUri(context, folder.rootUri) ?: return null
        if (folder.isRoot || folder.documentUri == folder.rootUri) return rootDoc
        return descendTo(rootDoc, folder.documentUri)
    }

    /**
     * Root에서 [target]에 닿는 폴더 객체를 BFS로 찾는다. 사용자가 폴더에 진입할 때만
     * 호출되므로 호출 빈도는 낮고, 깊이도 보통 작다 — 비용 OK.
     */
    private fun descendTo(root: DocumentFile, target: Uri): DocumentFile? {
        val queue = ArrayDeque<DocumentFile>().apply { add(root) }
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            for (child in node.listFiles()) {
                if (!child.isDirectory) continue
                if (child.uri == target) return child
                queue.add(child)
            }
        }
        return null
    }

    /**
     * [folder] 안 첫 책(자연 정렬) — 시리즈 그룹핑(폴더=시리즈)에서 폴더 행에 첫 권의
     * 표지를 보여주기 위해 사용. depth 1만 본다. 폴더가 비었거나 폴더만 있으면 null.
     */
    suspend fun firstBookIn(folder: FolderEntry): BookEntry? = withContext(Dispatchers.IO) {
        // listChildren는 폴더(이름순) → 책(이름순) 순으로 반환. 책 중 첫 항목.
        listChildren(folder).firstOrNull { it is BookEntry } as? BookEntry
    }

    /**
     * [folder] 안의 책(.cbz/.zip) 갯수를 재귀적으로 센다 — 폴더 행에 "N권"을
     * 미리 보여주기 위함. PRD §6.1 "중첩 아카이브 추출 최대 3단계" 그대로 [maxDepth] 제한.
     *
     * 비용: SAF `listFiles()`는 매 호출이 IPC라 깊은 트리에서 비싸다. 호출자(ViewModel)는
     * 화면에 보이는 폴더만, 결과는 in-memory 캐시에 저장해 중복 호출을 막아야 한다.
     */
    suspend fun countBooks(folder: FolderEntry, maxDepth: Int = 3): Int =
        withContext(Dispatchers.IO) {
            val parent = resolveFolder(folder) ?: return@withContext 0
            countBooksRecursive(parent, depth = 0, maxDepth = maxDepth)
        }

    private fun countBooksRecursive(node: DocumentFile, depth: Int, maxDepth: Int): Int {
        if (depth > maxDepth) return 0
        var count = 0
        for (child in node.listFiles()) {
            if (!child.isAcceptable()) continue
            if (child.isDirectory) {
                count += countBooksRecursive(child, depth + 1, maxDepth)
            } else if (child.isCbzOrZip()) {
                count += 1
            }
        }
        return count
    }

    private fun DocumentFile.isAcceptable(): Boolean {
        val n = name ?: return false
        // macOS AppleDouble (._book.cbz) / 닷파일(.DS_Store) 제외 — FAT/exFAT/SMB로
        // 옮길 때 Finder가 짝으로 만들어 두는 메타데이터.
        return !n.startsWith(".")
    }

    private fun DocumentFile.isCbzOrZip(): Boolean {
        val n = name ?: return false
        val ext = n.substringAfterLast('.', missingDelimiterValue = "").lowercase()
        return ext == "cbz" || ext == "zip"
    }
}
