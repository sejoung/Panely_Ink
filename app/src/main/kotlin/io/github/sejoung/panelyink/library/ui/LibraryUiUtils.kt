package io.github.sejoung.panelyink.library.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.sejoung.panelyink.ui.theme.PanelyInkColors

@Composable
internal fun HairlineDivider() {
  Box(
    modifier = Modifier
      .fillMaxWidth()
      .height(1.dp)
      .background(PanelyInkColors.Hairline),
  )
}

@Composable
internal fun stringResourceCompat(id: Int): String =
  androidx.compose.ui.res.stringResource(id)

@Composable
internal fun stringResourceCompat(id: Int, vararg formatArgs: Any): String =
  androidx.compose.ui.res.stringResource(id, *formatArgs)

internal fun formatBytes(bytes: Long): String {
  if (bytes <= 0) return "-"
  val mb = bytes / 1024.0 / 1024.0
  return if (mb >= 1.0) "%.1f MB".format(mb) else "%d KB".format(bytes / 1024)
}

internal tailrec fun resolveActivity(context: android.content.Context): android.app.Activity? =
  when (context) {
    is android.app.Activity -> context
    is android.content.ContextWrapper -> resolveActivity(context.baseContext)
    else -> null
  }
