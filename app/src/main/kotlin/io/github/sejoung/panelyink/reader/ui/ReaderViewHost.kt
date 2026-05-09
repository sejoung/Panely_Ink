package io.github.sejoung.panelyink.reader.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import io.github.sejoung.panelyink.reader.ReaderViewModel
import io.github.sejoung.panelyink.reader.session.CbzBookSession

@Composable
internal fun ReaderAndroidViewHost(
  session: CbzBookSession,
  viewModel: ReaderViewModel,
  onViewAttached: (ReaderView) -> Unit,
  onViewReleased: (ReaderView) -> Unit,
  modifier: Modifier = Modifier,
) {
  key(session) {
    AndroidView(
      modifier = modifier.fillMaxSize(),
      factory = { ctx ->
        ReaderView(ctx).also {
          it.attach(session, viewModel)
          onViewAttached(it)
        }
      },
      onRelease = { v ->
        v.detach()
        onViewReleased(v)
      },
    )
  }
}
