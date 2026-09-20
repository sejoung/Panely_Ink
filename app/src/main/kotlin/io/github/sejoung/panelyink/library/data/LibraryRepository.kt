package io.github.sejoung.panelyink.library.data

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import io.github.sejoung.panelyink.core.archive.CbzArchive
import io.github.sejoung.panelyink.core.book.IndexedBookRef
import io.github.sejoung.panelyink.core.sort.NaturalOrderComparator
import io.github.sejoung.panelyink.library.model.BookEntry
import io.github.sejoung.panelyink.library.model.FolderEntry
import io.github.sejoung.panelyink.library.model.LibraryEntry
import io.github.sejoung.panelyink.library.model.bookId
import io.github.sejoung.panelyink.library.model.toBookRef
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.util.zip.ZipException
import kotlin.coroutines.coroutineContext

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
   * zip의 `documentUri` → ZIP-of-CBZ 가상 폴더 변환 결과 캐시. 시리즈가 아닌 zip은
   * `result=null`로 명시 캐시. 한 번 ZIP을 열어 검사하면 프로세스 살아있는 동안 재검사 없이
   * 즉시 응답 → 라이브러리 폴더 위/아래 이동에서 매번 ZIP을 다시 안 연다.
   *
   * 새로고침([invalidateCache])으로도 비우지 않는다 — 대신 검사 시점의 [ZipSignature]
   * (이름/크기/수정시각)를 같이 저장해 두고, 재열거 결과와 다르면 miss로 취급해 그 zip만
   * 다시 연다. 이전엔 새로고침마다 라이브러리의 모든 .zip을 다시 열었다(외장 SD에서 권당
   * 100~500ms).
   */
  private val seriesCache = mutableMapOf<Uri, CachedSeries>()

  /** [listChildren]이 마지막으로 본 zip의 수정시각. provider가 안 주면 키 없음. */
  private val lastModifiedByUri = mutableMapOf<Uri, Long>()
  private val cacheLock = Any()

  /**
   * 사용자 명시 새로고침 시 폴더 열거 캐시 비우기. 시리즈 검사 결과는 signature로 자체
   * 검증하므로 유지.
   */
  fun invalidateCache() {
    synchronized(cacheLock) {
      childrenCache.clear()
    }
  }

  /** 전체 초기화용 — 시리즈 검사 결과까지 모두 비움. */
  fun invalidateAll() {
    synchronized(cacheLock) {
      childrenCache.clear()
      seriesCache.clear()
      lastModifiedByUri.clear()
    }
  }

  /**
   * `inspectZipForSeries` cache 동기 조회. 호출자가 IO/코루틴 비용 없이 cache 상태를
   * 미리 분기하기 위해 사용 — `batchInspectSeries`가 hit/miss를 갈라 hit은 즉시 entries
   * swap, miss만 launch + semaphore로 실제 ZIP open을 돌리도록 한다.
   *
   * - [SeriesLookup.Hit]: cache에 결과(시리즈 FolderEntry 또는 null=일반 zip)가 있음
   * - [SeriesLookup.Miss]: 미검사 — 호출자가 [inspectZipForSeries]로 진행
   * - [SeriesLookup.NotApplicable]: 이미 nested entry라 검사 대상 X
   */
  fun seriesCacheLookup(book: BookEntry): SeriesLookup {
    if (book.nestedEntryName != null) return SeriesLookup.NotApplicable
    return synchronized(cacheLock) {
      val cached = validSeriesCacheLocked(book)
      if (cached != null) SeriesLookup.Hit(cached.result) else SeriesLookup.Miss
    }
  }

  /** [cacheLock] 안에서만 호출. signature가 달라졌으면(zip 교체/수정) miss. */
  private fun validSeriesCacheLocked(book: BookEntry): CachedSeries? =
    seriesCache[book.documentUri]?.takeIf { it.signature == signatureLocked(book) }

  private fun signatureLocked(book: BookEntry): ZipSignature = ZipSignature(
    displayName = book.displayName,
    sizeBytes = book.sizeBytes,
    lastModified = lastModifiedByUri[book.documentUri] ?: 0L,
  )

  /** 사용자가 추가한 SAF 트리 [Uri]들을 첫 화면용 root 폴더 행으로 변환. */
  suspend fun listRoots(rootUris: List<Uri>): List<FolderEntry> = withContext(Dispatchers.IO) {
    rootUris.map { uri ->
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
   *
   * 열거 실패는 빈 목록으로 뭉갠다 — 실패와 "빈 폴더"를 구분해야 하는 호출자는
   * [listChildrenOrNull] 사용.
   */
  suspend fun listChildren(parent: FolderEntry): List<LibraryEntry> =
    listChildrenOrNull(parent).orEmpty()

  /**
   * [listChildren]과 같지만 **열거 실패 시 null**. 실패 = provider가 null cursor를 돌려주거나
   * (SD 언마운트) 예외를 던진 경우(폴더 삭제/이름변경 → IllegalArgumentException, 권한 회수 →
   * SecurityException 등). 실패 결과는 캐시하지 않는다 — 다음 호출에서 다시 시도.
   *
   * provider 예외는 여기(repository 경계)에서 모두 잡는다. 호출자는 plain
   * `viewModelScope.launch`라 새어 나가면 앱이 죽고, 영속된 마지막 path 때문에 재실행마다
   * 같은 폴더를 다시 열어 crash loop가 된다.
   */
  suspend fun listChildrenOrNull(parent: FolderEntry): List<LibraryEntry>? =
    withContext(Dispatchers.IO) {
      // 가상 폴더(ZIP-of-CBZ) — SAF query 없이 인스턴스가 들고 있는 nested 목록
      // 그대로. 정렬은 inspectZipForSeries가 이미 자연순으로 만들어놨다.
      parent.nestedBooks?.let { return@withContext it }
      // in-memory 캐시 hit → SAF query 0번
      synchronized(cacheLock) {
        childrenCache[parent.documentUri]
      }?.let { return@withContext it }
      val results = try {
        queryChildren(parent)
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        Log.w(TAG, "listChildren failed: ${parent.documentUri}", e)
        null
      }
      if (results == null) return@withContext null
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

  /** SAF children query 1회. null cursor(=provider 실패)면 null. provider 예외는 그대로 던짐. */
  private fun queryChildren(parent: FolderEntry): List<LibraryEntry>? {
    val parentDocId = parent.documentIdForChildren()
    val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
      parent.rootUri, parentDocId,
    )
    val results = mutableListOf<LibraryEntry>()
    val modified = mutableMapOf<Uri, Long>()
    val cursor = resolver.query(childrenUri, CHILD_PROJECTION, null, null, null)
      ?: return null
    cursor.use {
      val idCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
      val nameCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
      val mimeCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
      val sizeCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_SIZE)
      // 수정시각은 optional 컬럼 — 없는 provider도 있어 OrThrow 아님.
      val modifiedCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
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
          if (modifiedCol >= 0 && !cursor.isNull(modifiedCol)) {
            modified[childUri] = cursor.getLong(modifiedCol)
          }
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
    if (modified.isNotEmpty()) {
      synchronized(cacheLock) { lastModifiedByUri.putAll(modified) }
    }
    return results
  }

  /**
   * [folder] 안 첫 책(자연 정렬) — 시리즈 그룹핑(폴더=시리즈)에서 폴더 행에 첫 권의
   * 표지를 보여주기 위해 사용. depth 1만 본다. 폴더가 비었거나 폴더만 있으면 null.
   */
  suspend fun firstBookIn(folder: FolderEntry): BookEntry? = withContext(Dispatchers.IO) {
    listChildren(folder).firstOrNull { it is BookEntry } as? BookEntry
  }

  /**
   * 모든 root를 재귀로 훑어 책 목록을 만든다. [LibraryScanResult.complete]=false면 어딘가
   * (root/하위 폴더 열거, 시리즈 ZIP 검사, depth 초과)에서 실패해 목록이 **불완전**하다는 뜻 —
   * 호출자는 이 결과를 근거로 북마크/인덱스를 삭제하면 안 된다.
   */
  suspend fun listAllBooks(rootUris: List<Uri>, maxDepth: Int = 16): LibraryScanResult {
    val roots = listRoots(rootUris)
    val books = mutableListOf<IndexedBookRef>()
    var complete = true
    for (root in roots) {
      coroutineContext.ensureActive()
      if (!listAllBooksIn(root, depth = 0, maxDepth = maxDepth, out = books)) complete = false
    }
    return LibraryScanResult(books = books, complete = complete)
  }

  /**
   * [bookIds]에 해당하는 책을 찾을 때까지만 훑는다. 전부 찾으면 조기 종료.
   * [LibraryScanResult.complete]=false면 못 찾은 id가 "없는 책"이 아니라 "못 본 책"일 수 있다.
   */
  suspend fun findBooksByIds(
    rootUris: List<Uri>,
    bookIds: Set<String>,
    maxDepth: Int = 16,
  ): LibraryScanResult {
    if (bookIds.isEmpty()) return LibraryScanResult(emptyList(), complete = true)
    val remaining = bookIds.toMutableSet()
    val found = mutableListOf<IndexedBookRef>()
    val roots = listRoots(rootUris)
    var complete = true
    for (root in roots) {
      if (remaining.isEmpty()) break
      val ok = findBooksByIdsIn(
        folder = root,
        remaining = remaining,
        found = found,
        depth = 0,
        maxDepth = maxDepth,
      )
      if (!ok) complete = false
    }
    return LibraryScanResult(books = found, complete = complete)
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
  suspend fun inspectZipForSeries(book: BookEntry): FolderEntry? =
    (inspectZip(book) as? ZipInspection.Done)?.series

  /**
   * [inspectZipForSeries] 본체 — "시리즈 아님"([ZipInspection.Done] + null)과 "검사 실패"
   * ([ZipInspection.Failed])를 구분한다. 전체 스캔이 실패한 ZIP의 nested 책 북마크를 orphan으로
   * 오판해 지우지 않도록.
   *
   * - 손상된 ZIP([ZipException])은 결정적 실패 → "시리즈 아님"으로 캐시(매번 재오픈 방지)
   * - 그 외(IO 오류, 권한, OOM)는 일시적일 수 있어 캐시하지 않고 [ZipInspection.Failed]
   */
  private suspend fun inspectZip(book: BookEntry): ZipInspection = withContext(Dispatchers.IO) {
    // 이미 nested entry인 책은 ZIP-of-CBZ 자식이라 다시 검사 안 함.
    if (book.nestedEntryName != null) return@withContext ZipInspection.Done(null)
    val signature = synchronized(cacheLock) {
      validSeriesCacheLocked(book)?.let { return@withContext ZipInspection.Done(it.result) }
      signatureLocked(book)
    }
    val archive = try {
      CbzArchive.open(context, book.documentUri)
    } catch (e: CancellationException) {
      throw e
    } catch (e: ZipException) {
      Log.w(TAG, "inspect: broken zip: ${book.documentUri}", e)
      synchronized(cacheLock) {
        seriesCache[book.documentUri] = CachedSeries(signature, null)
      }
      return@withContext ZipInspection.Done(null)
    } catch (t: Throwable) {
      Log.w(TAG, "inspect open failed: ${book.documentUri}", t)
      return@withContext ZipInspection.Failed
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
      seriesCache[book.documentUri] = CachedSeries(signature, result)
    }
    ZipInspection.Done(result)
  }

  /** @return 이 폴더 이하를 빠짐없이 봤으면 true. 하나라도 실패/생략했으면 false. */
  private suspend fun listAllBooksIn(
    folder: FolderEntry,
    depth: Int,
    maxDepth: Int,
    out: MutableList<IndexedBookRef>,
  ): Boolean {
    if (depth > maxDepth) return false
    val children = listChildrenOrNull(folder) ?: return false
    var complete = true
    val directBooks = mutableListOf<BookEntry>()
    val childFolders = mutableListOf<FolderEntry>()

    for (entry in children) {
      // 큰 라이브러리에서 새로고침 연타/화면 이탈 시 즉시 멈추도록 entry 단위 취소 확인.
      coroutineContext.ensureActive()
      when (entry) {
        is FolderEntry -> childFolders += entry
        is BookEntry -> {
          val seriesFolder = if (
            entry.nestedEntryName == null &&
            entry.displayName.endsWith(".zip", ignoreCase = true)
          ) {
            when (val inspection = inspectZip(entry)) {
              is ZipInspection.Done -> inspection.series
              // 검사 실패 — 시리즈였다면 nested 책들을 못 본 것이므로 스캔 불완전.
              ZipInspection.Failed -> {
                complete = false
                null
              }
            }
          } else {
            null
          }
          if (seriesFolder != null) {
            val nestedBooks = seriesFolder.nestedBooks.orEmpty()
            out += nestedBooks.map {
              IndexedBookRef(
                book = it.toBookRef(),
                siblings = nestedBooks.map { nested -> nested.toBookRef() },
                groupKey = seriesFolder.documentUri.toString(),
              )
            }
          } else {
            directBooks += entry
          }
        }
      }
    }

    out += directBooks.map {
      IndexedBookRef(
        book = it.toBookRef(),
        siblings = directBooks.map { book -> book.toBookRef() },
        groupKey = folder.documentUri.toString(),
      )
    }
    for (child in childFolders) {
      if (!listAllBooksIn(child, depth = depth + 1, maxDepth = maxDepth, out = out)) {
        complete = false
      }
    }
    return complete
  }

  private suspend fun findBooksByIdsIn(
    folder: FolderEntry,
    remaining: MutableSet<String>,
    found: MutableList<IndexedBookRef>,
    depth: Int,
    maxDepth: Int,
  ): Boolean {
    if (remaining.isEmpty()) return true
    if (depth > maxDepth) return false
    val children = listChildrenOrNull(folder) ?: return false
    var complete = true
    val directBooks = mutableListOf<BookEntry>()
    val childFolders = mutableListOf<FolderEntry>()

    for (entry in children) {
      if (remaining.isEmpty()) break
      coroutineContext.ensureActive()
      when (entry) {
        is FolderEntry -> childFolders += entry
        is BookEntry -> {
          val seriesFolder = if (
            entry.nestedEntryName == null &&
            entry.displayName.endsWith(".zip", ignoreCase = true)
          ) {
            when (val inspection = inspectZip(entry)) {
              is ZipInspection.Done -> inspection.series
              ZipInspection.Failed -> {
                complete = false
                null
              }
            }
          } else {
            null
          }
          if (seriesFolder != null) {
            val nestedBooks = seriesFolder.nestedBooks.orEmpty()
            for (nested in nestedBooks) {
              val bookId = nested.bookId.value
              if (remaining.remove(bookId)) {
                found += IndexedBookRef(
                  book = nested.toBookRef(),
                  siblings = nestedBooks.map { it.toBookRef() },
                  groupKey = seriesFolder.documentUri.toString(),
                )
                if (remaining.isEmpty()) break
              }
            }
          } else {
            directBooks += entry
          }
        }
      }
    }

    for (book in directBooks) {
      val bookId = book.bookId.value
      if (remaining.remove(bookId)) {
        found += IndexedBookRef(
          book = book.toBookRef(),
          siblings = directBooks.map { it.toBookRef() },
          groupKey = folder.documentUri.toString(),
        )
        if (remaining.isEmpty()) return true
      }
    }
    for (child in childFolders) {
      if (remaining.isEmpty()) return true
      val ok = findBooksByIdsIn(
        folder = child,
        remaining = remaining,
        found = found,
        depth = depth + 1,
        maxDepth = maxDepth,
      )
      if (!ok) complete = false
    }
    return complete
  }

  /**
   * [folder] 안의 책(.cbz/.zip) 갯수를 재귀적으로 센다 — 폴더 행에 "N권"을
   * 미리 보여주기 위함. PRD §6.1 "중첩 아카이브 추출 최대 3단계" 그대로 [maxDepth] 제한.
   *
   * [listChildren] 결과를 활용 → in-memory 캐시 hit 시 SAF query 0번. 폴더 트리
   * 위/아래 이동에서 큰 효과(외장 SD card).
   *
   * @return 열거가 하나라도 실패하면 null — 호출자가 틀린 "0권"을 캐시하지 않도록.
   */
  suspend fun countBooks(folder: FolderEntry, maxDepth: Int = 3): Int? =
    withContext(Dispatchers.IO) { countBooksRec(folder, depth = 0, maxDepth) }

  private suspend fun countBooksRec(
    folder: FolderEntry,
    depth: Int,
    maxDepth: Int,
  ): Int? {
    if (depth > maxDepth) return 0
    val children = listChildrenOrNull(folder) ?: return null
    val bookCount = children.count { it is BookEntry }
    var subCount = 0
    for (child in children) {
      if (child is FolderEntry) {
        subCount += countBooksRec(child, depth + 1, maxDepth) ?: return null
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

  /** root 표시 이름. provider 예외(권한 회수/SD 언마운트)는 null로 — 호출자가 URI로 폴백. */
  private fun queryRootName(treeUri: Uri): String? = try {
    val docId = DocumentsContract.getTreeDocumentId(treeUri)
    val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
    resolver.query(
      docUri,
      arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
      null, null, null,
    )?.use { cursor ->
      if (cursor.moveToFirst()) cursor.getString(0) else null
    }
  } catch (e: Exception) {
    Log.w(TAG, "queryRootName failed: $treeUri", e)
    null
  }

  private fun isCbzOrZipName(name: String): Boolean {
    val ext = name.substringAfterLast('.', missingDelimiterValue = "").lowercase()
    return ext == "cbz" || ext == "zip"
  }

  /** 시리즈 검사 시점의 zip 식별 정보 — 재열거 결과와 다르면 캐시 무효. */
  private data class ZipSignature(
    val displayName: String,
    val sizeBytes: Long,
    val lastModified: Long,
  )

  /** [result]=null은 "검사했고 시리즈 아님". */
  private data class CachedSeries(val signature: ZipSignature, val result: FolderEntry?)

  private sealed interface ZipInspection {
    /** 검사 완료. [series]=null이면 일반 책. */
    data class Done(val series: FolderEntry?) : ZipInspection

    /** ZIP을 열지 못함(일시적일 수 있음) — 시리즈 여부 모름. */
    data object Failed : ZipInspection
  }

  companion object {
    private const val TAG = "PanelyInk.Library"

    private val CHILD_PROJECTION = arrayOf(
      DocumentsContract.Document.COLUMN_DOCUMENT_ID,
      DocumentsContract.Document.COLUMN_DISPLAY_NAME,
      DocumentsContract.Document.COLUMN_MIME_TYPE,
      DocumentsContract.Document.COLUMN_SIZE,
      DocumentsContract.Document.COLUMN_LAST_MODIFIED,
    )
  }
}

/**
 * 라이브러리 전체 스캔 결과. [complete]=false면 [books]는 부분 목록 — "없는 책" 판정
 * (북마크/인덱스 orphan 삭제)에 쓰면 안 된다.
 */
data class LibraryScanResult(
  val books: List<IndexedBookRef>,
  val complete: Boolean,
)

/** [LibraryRepository.seriesCacheLookup] 결과. */
sealed interface SeriesLookup {
  /** cache hit. [result]=null은 "이미 검사했고 시리즈 아님"을 의미. */
  data class Hit(val result: FolderEntry?) : SeriesLookup
  data object Miss : SeriesLookup
  data object NotApplicable : SeriesLookup
}
