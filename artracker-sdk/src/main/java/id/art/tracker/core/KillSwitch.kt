package id.art.tracker.core

internal class KillSwitch(private val store: KeyValueStore, private val clock: Clock) {
    /**
     * In-memory value set by [applyOptedOut]; wins over the persisted flag so every reader in this process (the
     * facade, the flusher, the timer) sees a change at once, before it has been written to disk.
     */
    @Volatile
    private var optedOutOverride: Boolean? = null

    /** Effective opt-out: the in-memory value if one was set, otherwise the persisted flag. */
    var optedOut: Boolean
        get() = optedOutOverride ?: (store.getLong(KEY_OPTED_OUT, 0L) != 0L)
        set(value) {
            applyOptedOut(value)
            persistOptedOut()
        }

    /** Flips the in-memory flag only: instant and free of I/O. Follow with [persistOptedOut]. */
    fun applyOptedOut(value: Boolean) {
        optedOutOverride = value
    }

    /** Writes the current effective value (`commit()`: blocking disk I/O, never call on the main thread). */
    @Synchronized
    fun persistOptedOut() {
        store.putLong(KEY_OPTED_OUT, if (optedOut) 1L else 0L)
    }

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
