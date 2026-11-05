package id.art.tracker.core

internal class SessionManager(
    private val store: KeyValueStore,
    private val clock: Clock,
    private val timeoutMs: Long,
    private val maxDurationMs: Long = DEFAULT_MAX_DURATION_MS,
    private val listener: Listener,
) {
    interface Listener {
        fun onSessionStart(id: String)

        fun onSessionEnd(id: String, durationMs: Long)
    }

    private var id: String? = store.getString(KEY_ID)
    private var startedAt: Long = store.getLong(KEY_STARTED_AT, 0L)
    private var lastActive: Long = store.getLong(KEY_LAST_ACTIVE, 0L)
    private var lastPersistedActive: Long = lastActive
    private var foreground = false

    private sealed class Event {
        class Start(val id: String) : Event()

        class End(val id: String, val durationMs: Long) : Event()
    }

    /** Current session id; starts a session if none exists and rotates it past the hard cap. */
    fun currentId(): String {
        val events = ArrayList<Event>(2)
        val result =
            synchronized(this) {
                val now = clock.nowMs()
                val current = id
                when {
                    current == null -> start(now, events)
                    now - startedAt > maxDurationMs -> {
                        end(current, now - startedAt, events)
                        start(now, events)
                    }
                    else -> {
                        if (foreground) touch(now)
                        current
                    }
                }
            }
        dispatch(events)
        return result
    }

    fun onForeground() {
        val events = ArrayList<Event>(2)
        synchronized(this) {
            val now = clock.nowMs()
            val current = id
            if (current != null && now - lastActive > timeoutMs) {
                end(current, lastActive - startedAt, events)
                start(now, events)
            } else if (current == null) {
                start(now, events)
            }
            foreground = true
            touch(now, force = true)
        }
        dispatch(events)
    }

    @Synchronized
    fun onBackground() {
        foreground = false
        if (id != null) touch(clock.nowMs(), force = true)
    }

    fun rotate() {
        val events = ArrayList<Event>(2)
        synchronized(this) {
            val now = clock.nowMs()
            id?.let { end(it, now - startedAt, events) }
            start(now, events)
        }
        dispatch(events)
    }

    // State transitions are applied atomically under the lock; the listener is only invoked afterwards
    // (outside the lock, in order) so callbacks may call back into this manager or take other locks.
    private fun dispatch(events: List<Event>) {
        for (e in events) {
            when (e) {
                is Event.Start -> listener.onSessionStart(e.id)
                is Event.End -> listener.onSessionEnd(e.id, e.durationMs)
            }
        }
    }

    private fun start(now: Long, events: MutableList<Event>): String {
        val fresh = UuidV7.generate(now)
        id = fresh
        startedAt = now
        lastActive = now
        lastPersistedActive = now
        store.putString(KEY_ID, fresh)
        store.putLong(KEY_STARTED_AT, now)
        store.putLong(KEY_LAST_ACTIVE, now)
        events += Event.Start(fresh)
        return fresh
    }

    private fun end(endedId: String, durationMs: Long, events: MutableList<Event>) {
        id = null
        store.putString(KEY_ID, null)
        events += Event.End(endedId, durationMs.coerceAtLeast(0L))
    }

    /** Persisting is throttled so a process killed mid-foreground still has a recent activity mark. */
    private fun touch(now: Long, force: Boolean = false) {
        lastActive = now
        if (force || now - lastPersistedActive >= PERSIST_INTERVAL_MS) {
            lastPersistedActive = now
            store.putLong(KEY_LAST_ACTIVE, now)
        }
    }

    private companion object {
        const val KEY_ID = "session_id"
        const val KEY_STARTED_AT = "session_started_at"
        const val KEY_LAST_ACTIVE = "session_last_active"
        const val DEFAULT_MAX_DURATION_MS = 86_400_000L
        const val PERSIST_INTERVAL_MS = 60_000L
    }
}
