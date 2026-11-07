package id.art.tracker.playground.labs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CrashNoteTest {
    private class FakeNotes(override var pending: String? = null) : NoteStore

    private val at = 1_794_045_600_000L // 2026-11-07 10:00:00 UTC

    @Test
    fun consumeReturnsTheNoteOnceThenNull() {
        val note = CrashNote(FakeNotes()) { at }
        note.recordBeforeCrash("main thread")
        assertTrue(note.consume() != null)
        assertNull(note.consume())
    }

    @Test
    fun theNoteNamesTheKindAndTheInjectedTime() {
        val store = FakeNotes()
        CrashNote(store) { at }.recordBeforeCrash("background thread")
        val text = store.pending!!
        assertTrue(text, text.contains("background thread"))
        assertTrue(text, text.contains("2026-11-07 10:00:00"))
    }

    @Test
    fun aSecondRecordOverwritesTheFirst() {
        val store = FakeNotes()
        val note = CrashNote(store) { at }
        note.recordBeforeCrash("first")
        note.recordBeforeCrash("second")
        assertTrue(note.consume()!!.contains("second"))
    }

    @Test
    fun consumeWithoutARecordIsNull() {
        assertNull(CrashNote(FakeNotes()) { at }.consume())
    }

    @Test
    fun sessionAdviceWaitsLongerThanTheTimeout() {
        assertEquals(12, SessionAdvice.waitSeconds(10_000))
        assertEquals(3, SessionAdvice.waitSeconds(1_000))
        assertEquals(1_802, SessionAdvice.waitSeconds(1_800_000))
    }
}
