package id.art.tracker.playground.labs

import id.art.tracker.playground.inspector.InspectedItem
import id.art.tracker.playground.inspector.ItemSource
import org.junit.Assert.assertEquals
import org.junit.Test

class FingerprintCounterTest {
    private fun item(name: String, fingerprint: String? = null) = InspectedItem(
        id = "i", type = "log", name = name, level = "error", message = null, timestampMs = 0L, sessionId = "s", userId = null,
        attributes = if (fingerprint == null) emptyMap() else mapOf("fingerprint" to fingerprint),
        truncated = false, source = ItemSource.SENT_PAYLOAD,
    )

    @Test
    fun groupsExceptionItemsByFingerprintAndIgnoresOthers() {
        val items = List(15) { item("exception", "f1") } + List(3) { item("exception", "f2") } + List(5) { item("track") }
        assertEquals(mapOf("f1" to 15, "f2" to 3), FingerprintCounter.countExceptions(items))
    }

    @Test
    fun exceptionWithoutAFingerprintIsCountedUnderAQuestionMark() {
        assertEquals(mapOf("?" to 1), FingerprintCounter.countExceptions(listOf(item("exception"))))
    }

    @Test
    fun emptyInputGivesAnEmptyMap() {
        assertEquals(emptyMap<String, Int>(), FingerprintCounter.countExceptions(emptyList()))
    }
}
