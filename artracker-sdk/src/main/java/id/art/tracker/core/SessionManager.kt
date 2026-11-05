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

    /** Current session id; starts a session if none exists and rotates it past the hard cap. */
    @Synchronized
    fun currentId(): String {
        val now = clock.nowMs()
        val current = id
        if (current == null) return start(now)
        if (now - startedAt > maxDurationMs) {
            end(current, now - startedAt)
            return start(now)
        }
        if (foreground) touch(now)
        return current
    }

    @Synchronized
    fun onForeground() {
        val now = clock.nowMs()
        val current = id
        if (current != null && now - lastActive > timeoutMs) {
            end(current, lastActive - startedAt)
            start(now)
        } else if (current == null) {
            start(now)
        }
        foreground = true
        touch(now, force = true)
    }

    @Synchronized
    fun onBackground() {
        foreground = false
        if (id != null) touch(clock.nowMs(), force = true)
    }

    @Synchronized
    fun rotate() {
        val now = clock.nowMs()
        id?.let { end(it, now - startedAt) }
        start(now)
    }

    private fun start(now: Long): String {
        val fresh = UuidV7.generate(now)
        id = fresh
        startedAt = now
        lastActive = now
        lastPersistedActive = now
        store.putString(KEY_ID, fresh)
        store.putLong(KEY_STARTED_AT, now)
        store.putLong(KEY_LAST_ACTIVE, now)
        listener.onSessionStart(fresh)
        return fresh
    }

    private fun end(endedId: String, durationMs: Long) {
        id = null
        store.putString(KEY_ID, null)
        listener.onSessionEnd(endedId, durationMs.coerceAtLeast(0L))
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
