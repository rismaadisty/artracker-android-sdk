package id.art.tracker.core

import java.util.UUID

internal class IdentityStore(private val store: KeyValueStore) {
    @Volatile
    private var anonymous: String = store.getString(KEY_ANONYMOUS_ID) ?: newAnonymousId()

    val anonymousId: String get() = anonymous

    @Volatile
    private var user: String? = store.getString(KEY_USER_ID)

    var userId: String?
        get() = user
        set(value) {
            user = value
            store.putString(KEY_USER_ID, value)
        }

    /** In-memory only; never persisted. */
    @Volatile
    var traits: Map<String, Any?> = emptyMap()

    fun reset() {
        userId = null
        traits = emptyMap()
        anonymous = newAnonymousId()
    }

    private fun newAnonymousId(): String = UUID.randomUUID().toString().also { store.putString(KEY_ANONYMOUS_ID, it) }

    private companion object {
        const val KEY_ANONYMOUS_ID = "anonymous_id"
        const val KEY_USER_ID = "user_id"
    }
}
