package id.art.tracker.lifecycle

import org.junit.Assert.assertEquals
import org.junit.Test

/** The answer-or-timeout race of `ApplicationStartInfoSource`, without an API 35 device. */
class StartInfoCompletionTest {
    private val results = ArrayList<StartupTimestamps?>()
    private var removed = 0
    private var timeoutCancelled = 0
    private val stamps = StartupTimestamps(forkMs = 1, bindApplicationMs = 2, applicationOnCreateMs = 3)

    private fun completion() = StartInfoCompletion(
        removeListener = { removed++ },
        cancelTimeout = { timeoutCancelled++ },
        onResult = { results += it },
    )

    @Test
    fun anAnswer_isDeliveredAndTheListenerIsLeftToThePlatform() {
        // Review: ActivityManager dispatches with an index loop and clears the list itself; removing our entry from
        // inside the callback skips the next listener (the host app's own).
        val c = completion()
        c.answered(stamps)

        assertEquals(listOf<StartupTimestamps?>(stamps), results)
        assertEquals("the platform removes a listener it called", 0, removed)
        assertEquals(1, timeoutCancelled)
    }

    @Test
    fun aTimeout_removesTheListenerAndAnswersNull() {
        val c = completion()
        c.timedOut()

        assertEquals(listOf<StartupTimestamps?>(null), results)
        assertEquals(1, removed)
    }

    @Test
    fun onlyTheFirstOutcountCounts() {
        val c = completion()
        c.answered(stamps)
        c.timedOut()
        c.answered(null)

        assertEquals(1, results.size)
        assertEquals(0, removed)
    }

    @Test
    fun aFailureToRegister_answersNullWithoutRemovingAnything() {
        val c = completion()
        c.answered(null)

        assertEquals(listOf<StartupTimestamps?>(null), results)
        assertEquals(0, removed)
    }
}
