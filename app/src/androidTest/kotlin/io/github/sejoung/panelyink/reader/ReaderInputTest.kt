package io.github.sejoung.panelyink.reader

import android.view.KeyEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReaderInputTest {

    @Test
    fun nextKeysInvokeNext() {
        var prev = 0
        var next = 0

        val consumed = ReaderInput.dispatch(
            event = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOLUME_DOWN),
            onPrev = { prev += 1 },
            onNext = { next += 1 },
        )

        assertTrue(consumed)
        assertEquals(0, prev)
        assertEquals(1, next)
    }

    @Test
    fun previousKeysInvokePrevious() {
        var prev = 0
        var next = 0

        val consumed = ReaderInput.dispatch(
            event = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_PAGE_UP),
            onPrev = { prev += 1 },
            onNext = { next += 1 },
        )

        assertTrue(consumed)
        assertEquals(1, prev)
        assertEquals(0, next)
    }

    @Test
    fun actionUpForMappedKeyIsConsumedWithoutCallback() {
        var calls = 0

        val consumed = ReaderInput.dispatch(
            event = KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_RIGHT),
            onPrev = { calls += 1 },
            onNext = { calls += 1 },
        )

        assertTrue(consumed)
        assertEquals(0, calls)
    }

    @Test
    fun unmappedKeyIsIgnored() {
        val consumed = ReaderInput.dispatch(
            event = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A),
            onPrev = { error("unexpected") },
            onNext = { error("unexpected") },
        )

        assertFalse(consumed)
    }
}
