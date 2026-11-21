package id.art.tracker.replay

import org.junit.Assert.assertEquals
import org.junit.Test

class ErrorTriggerPolicyTest {
    @Test
    fun admitsWithGapAndPerSessionCap() {
        val policy = ErrorTriggerPolicy()
        val rows = listOf(
            Triple("s1", 0L, true),
            Triple("s1", 1_000L, false),
            Triple("s1", 60_000L, true),
            Triple("s1", 120_000L, true),
            Triple("s1", 999_000L, false),
            Triple("s2", 999_500L, true),
        )
        rows.forEachIndexed { index, (sessionId, nowMs, expected) ->
            assertEquals("row ${index + 1}", expected, policy.admit(sessionId, nowMs))
        }
    }
}
