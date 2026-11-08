package id.art.tracker.testutil

import id.art.tracker.core.Clock

internal class FakeClock(var now: Long = 0) : Clock {
    override fun nowMs(): Long = now

    fun advance(ms: Long) {
        now += ms
    }
}
