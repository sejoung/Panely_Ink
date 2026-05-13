package io.github.sejoung.panelyink.library

import kotlinx.coroutines.Job
import org.junit.Assert.assertTrue
import org.junit.Test

class CoverJobCancellationTest {

    @Test
    fun cancelAndClearJobs_cancelsEveryJobAndClearsMap() {
        val first = Job()
        val second = Job()
        val jobs: MutableMap<String, Job> = mutableMapOf(
            "first" to first,
            "second" to second,
        )

        cancelAndClearJobs(jobs)

        assertTrue(first.isCancelled)
        assertTrue(second.isCancelled)
        assertTrue(jobs.isEmpty())
    }
}
