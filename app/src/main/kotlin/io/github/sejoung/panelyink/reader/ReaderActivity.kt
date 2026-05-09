package io.github.sejoung.panelyink.reader

import android.content.Context
import android.content.ContextWrapper
import io.github.sejoung.panelyink.MainActivity

/** ContextWrapper 체인을 따라 올라가 [MainActivity]를 찾는다. */
internal tailrec fun Context.findMainActivity(): MainActivity? = when (this) {
    is MainActivity -> this
    is ContextWrapper -> baseContext.findMainActivity()
    else -> null
}
