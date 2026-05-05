package io.github.sejoung.panelyink.library

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import io.github.sejoung.panelyink.core.sort.NaturalOrderComparator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * SAF 트리 URI들을 받아 depth-1 위치의 .cbz / .zip 파일을 열거한다.
 *
 * v1.0 스코프 (PRD §6.1):
 * - depth-1만. 시리즈 그룹핑 / 하위 폴더 탐색은 v1.0 후반(M3) 또는 M4
 * - CBR(.rar)은 v1.5로 미룸 (junrar GPL 격리 비용)
 */
class LibraryRepository(private val context: Context) {

    suspend fun scan(rootUris: List<Uri>): List<LibraryEntry> = withContext(Dispatchers.IO) {
        rootUris.flatMap { rootUri ->
            val tree = DocumentFile.fromTreeUri(context, rootUri) ?: return@flatMap emptyList()
            tree.listFiles().asSequence()
                .filter { it.isFile && it.isCbzOrZip() }
                .map { df ->
                    LibraryEntry(
                        documentUri = df.uri,
                        displayName = df.name ?: "(unknown)",
                        sizeBytes = df.length(),
                        mimeType = df.type,
                        rootUri = rootUri,
                    )
                }
                .toList()
        }.sortedWith(compareBy(NaturalOrderComparator) { it.displayName })
    }

    private fun DocumentFile.isCbzOrZip(): Boolean {
        val n = name ?: return false
        // macOS AppleDouble (._book.cbz) 와 닷파일(.DS_Store) 제외.
        // FAT/exFAT/SMB로 옮길 때 Finder가 짝으로 만들어 두는 메타데이터.
        if (n.startsWith(".")) return false
        val ext = n.substringAfterLast('.', missingDelimiterValue = "").lowercase()
        return ext == "cbz" || ext == "zip"
    }
}
