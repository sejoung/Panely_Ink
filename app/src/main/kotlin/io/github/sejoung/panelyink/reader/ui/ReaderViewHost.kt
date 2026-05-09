package io.github.sejoung.panelyink.reader.ui

import io.github.sejoung.panelyink.reader.ReaderState
import io.github.sejoung.panelyink.reader.ReaderViewModel
import io.github.sejoung.panelyink.reader.input.ReaderInput
import io.github.sejoung.panelyink.reader.model.BookSettings
import io.github.sejoung.panelyink.reader.model.ReadingDirection
import io.github.sejoung.panelyink.reader.model.SeriesContext
import io.github.sejoung.panelyink.reader.session.CbzBookSession
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

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
