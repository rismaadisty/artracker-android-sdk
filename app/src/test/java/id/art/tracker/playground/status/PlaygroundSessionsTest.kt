package id.art.tracker.playground.status

import org.junit.Assert.assertSame
import org.junit.Test

class PlaygroundSessionsTest {
    @Test
    fun theHistoryIsOneSharedInstance() {
        assertSame(PlaygroundSessions.history, PlaygroundSessions.history)
    }
}
