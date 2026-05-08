package io.github.sejoung.panelyink.library

import android.content.Context
import android.net.Uri
import android.util.Log
import io.github.sejoung.panelyink.core.archive.CbzArchive
import io.github.sejoung.panelyink.core.position.PositionKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * ZIP-of-CBZ에서 nested entry를 단일 cbz 파일로 추출. PRD §6.1 "중첩 아카이브 추출
 * 최대 3단계"의 1차 구현.
 *
 * 방식: Apache Commons Compress가 nested entry에 대해 random access를 지원하지
 * 않아(streaming만), 임시 파일로 1회 추출 후 그 파일을 일반 [CbzArchive]로 연다.
 *
 * 캐시 위치: `cacheDir/nested/<sha1(parentUri)>__<entryHash>.cbz`
 *  - `cacheDir`을 사용 — OS가 공간 부족 시 자동 정리해도 문제 없음(다음 진입에서 재추출)
 *  - bookId 체계와 별개의 파일명 — `cacheDir`은 사용자 데이터 아님
 *
 * 비용 메모:
 * - 외장 SD에서 큰 nested cbz(50MB+) 추출은 수 초. 첫 진입 1회만.
 * - 캐시 hit 시 즉시 반환.
 *
 * 호출자(`CbzBookSession.open`)가 추출된 [File]을 받아 [CbzArchive.open(File)]로 사용.
 */
object NestedZipExtractor {

    private const val DIR = "nested"
    private const val EXT = "cbz"
    private const val TAG = "PanelyInk.NestedExtractor"

    /**
     * nested entry를 임시 파일로 추출. 이미 캐시에 있으면 그대로 반환(추출 X).
     *
     * @return 추출된 cbz 파일. 추출 실패 시 예외.
     */
    suspend fun extract(
        context: Context,
        parentUri: Uri,
        entryName: String,
    ): File = withContext(Dispatchers.IO) {
        val target = cacheFile(context, parentUri, entryName)
        if (target.exists() && target.length() > 0) {
            Log.d(TAG, "cache hit: ${target.name}")
            return@withContext target
        }
        target.parentFile?.mkdirs()

        Log.d(TAG, "extract start: parent=$parentUri entry=$entryName")
        val t0 = System.currentTimeMillis()
        val archive = CbzArchive.open(context, parentUri)
        try {
            archive.openNestedEntry(entryName).use { input ->
                FileOutputStream(target).use { output ->
                    input.copyTo(output)
                }
            }
        } catch (t: Throwable) {
            // 부분 파일 정리
            runCatching { if (target.exists()) target.delete() }
            throw t
        } finally {
            archive.close()
        }
        Log.d(
            TAG,
            "extract done: ${target.length()} bytes in ${System.currentTimeMillis() - t0}ms",
        )
        target
    }

    /** 캐시 파일 경로 — 부모 URI + entry name으로 unique. */
    fun cacheFile(context: Context, parentUri: Uri, entryName: String): File {
        val parentHash = PositionKey.bookIdFromUri(parentUri.toString())
        val entryHash = PositionKey.bookIdFromUri(entryName)
        val dir = File(context.cacheDir, DIR)
        return File(dir, "${parentHash}__${entryHash}.$EXT")
    }

    /** 사용자 명시 정리 — `clearCoverCache`/`resetAllData`와 같이 호출되면 좋음. */
    suspend fun clearAll(context: Context): Int = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, DIR)
        if (!dir.exists()) return@withContext 0
        var deleted = 0
        dir.listFiles()?.forEach { if (it.delete()) deleted++ }
        deleted
    }
}
