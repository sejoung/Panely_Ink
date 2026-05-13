package io.github.sejoung.panelyink.reader.ui

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderScreenResourceTest {

    @Test
    fun closeResourceOnFailure_keepsResourceOpenOnSuccess() = runTest {
        val resource = CloseTrackingResource()

        val result = closeResourceOnFailure(
            open = { resource },
            close = { it.close() },
        ) {
            "ready"
        }

        assertEquals("ready", result)
        assertFalse(resource.closed)
    }

    @Test
    fun closeResourceOnFailure_closesResourceWhenBlockThrows() = runTest {
        val resource = CloseTrackingResource()
        val failure = IllegalStateException("decode failed")

        val thrown = runCatching {
            closeResourceOnFailure(
                open = { resource },
                close = { it.close() },
            ) {
                throw failure
            }
        }.exceptionOrNull()

        assertSame(failure, thrown)
        assertTrue(resource.closed)
    }

    @Test
    fun closeResourceOnFailure_closesResourceAndRethrowsCancellation() = runTest {
        val resource = CloseTrackingResource()
        val cancellation = CancellationException("screen left")

        val thrown = runCatching {
            closeResourceOnFailure(
                open = { resource },
                close = { it.close() },
            ) {
                throw cancellation
            }
        }.exceptionOrNull()

        assertSame(cancellation, thrown)
        assertTrue(resource.closed)
    }

    @Test
    fun closeResourceOnFailure_doesNotCloseWhenOpenFails() = runTest {
        var closeCalls = 0
        val failure = IllegalStateException("open failed")

        val thrown = runCatching {
            closeResourceOnFailure<CloseTrackingResource, Unit>(
                open = { throw failure },
                close = { closeCalls++ },
            ) {}
        }.exceptionOrNull()

        assertSame(failure, thrown)
        assertEquals(0, closeCalls)
    }

    private class CloseTrackingResource {
        var closed = false

        fun close() {
            closed = true
        }
    }
}
