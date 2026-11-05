package id.art.tracker.core

internal class KillSwitch(private val store: KeyValueStore, private val clock: Clock) {
    var optedOut: Boolean
        get() = store.getLong(KEY_OPTED_OUT, 0L) != 0L
        set(value) = store.putLong(KEY_OPTED_OUT, if (value) 1L else 0L)

    fun isActive(): Boolean = optedOut || clock.nowMs() < store.getLong(KEY_DISABLED_UNTIL, 0L)

    fun trip(durationMs: Long = DEFAULT_TRIP_MS) {
        store.putLong(KEY_DISABLED_UNTIL, clock.nowMs() + durationMs)
    }

    companion object {
        const val KEY_DISABLED_UNTIL = "disabled_until"
        const val KEY_OPTED_OUT = "opted_out"
        const val DEFAULT_TRIP_MS = 3_600_000L
    }
}
