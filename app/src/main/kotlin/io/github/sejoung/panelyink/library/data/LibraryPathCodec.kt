package io.github.sejoung.panelyink.library.data

import android.annotation.SuppressLint
import android.net.Uri
import io.github.sejoung.panelyink.library.model.FolderEntry
import org.json.JSONArray
import org.json.JSONObject

/**
 * 마지막으로 보던 path의 JSON 직렬화.
 *
 * ZIP-of-CBZ 가상 폴더는 `nestedBooks` 목록 자체를 저장하지 않고 "가상 폴더" 표식
 * ([JSON_VIRTUAL])만 남긴다 — decode 결과는 `nestedBooks = emptyList()`(표식 역할)이며,
 * 복원하는 쪽(LibraryViewModel)이 ZIP을 다시 검사해 실제 목록으로 채우거나 실패 시 path
 * 꼬리에서 떼어낸다. 표식이 없는 예전 포맷도 그대로 decode된다(일반 폴더로).
 */
internal object LibraryPathCodec {
  fun isValid(path: List<FolderEntry>, currentRoots: List<Uri>): Boolean {
    if (path.isEmpty()) return false
    val firstRoot = path.first().rootUri
    return firstRoot in currentRoots && path.all { it.rootUri == firstRoot }
  }

  fun encode(path: List<FolderEntry>): String {
    val arr = JSONArray()
    for (f in path) {
      arr.put(JSONObject().apply {
        put(JSON_NAME, f.displayName)
        put(JSON_DOC, f.documentUri.toString())
        put(JSON_ROOT, f.rootUri.toString())
        put(JSON_IS_ROOT, f.isRoot)
        if (f.nestedBooks != null) put(JSON_VIRTUAL, true)
      })
    }
    return arr.toString()
  }

  @SuppressLint("UseKtx")
  fun decode(raw: String?): List<FolderEntry> {
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
          nestedBooks = if (o.optBoolean(JSON_VIRTUAL, false)) emptyList() else null,
        )
      }
    }.getOrElse { emptyList() }
  }

  private const val JSON_NAME = "name"
  private const val JSON_DOC = "doc"
  private const val JSON_ROOT = "root"
  private const val JSON_IS_ROOT = "isRoot"
  private const val JSON_VIRTUAL = "virtualZip"
}
