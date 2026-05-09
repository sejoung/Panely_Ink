package io.github.sejoung.panelyink.library.data

import android.annotation.SuppressLint
import android.net.Uri
import io.github.sejoung.panelyink.library.model.FolderEntry
import org.json.JSONArray
import org.json.JSONObject

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
        )
      }
    }.getOrElse { emptyList() }
  }

  private const val JSON_NAME = "name"
  private const val JSON_DOC = "doc"
  private const val JSON_ROOT = "root"
  private const val JSON_IS_ROOT = "isRoot"
}
