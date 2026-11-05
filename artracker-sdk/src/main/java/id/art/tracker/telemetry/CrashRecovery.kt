package id.art.tracker.telemetry

import id.art.tracker.core.SdkLog
import id.art.tracker.transport.ItemQueue
import id.art.tracker.transport.StoredItem
import org.json.JSONObject

/**
 * Turns the crash files of earlier runs into queue items (ARCHITECTURE.md section 8.1 step 4). Runs on the SDK
 * scope at startup, before the initial flush decision.
 */
internal object CrashRecovery {
    /** Crash and fatal items: first to be sent, never evicted. */
    private const val CRASH_PRIORITY = 0

    /**
     * For each pending file: validate, insert into [queue] as a priority-0 item (the file content already is the
     * final item JSON), and delete the file only after `insert` returned, so a failure or a kill in between
     * leaves the file for the next start (the item id makes a double insert a no-op).
     * A file that is unreadable or not a valid crash item is deleted without being inserted, so one poison
     * file can neither be retried forever nor spoil the others. Returns the number of items inserted.
     */
    fun recover(store: CrashStore, queue: ItemQueue): Int {
        var recovered = 0
        for (file in store.pending()) {
            try {
                val json = store.read(file)
                val id = json?.let(::validatedId)
                if (id == null) {
                    SdkLog.w("dropping an invalid crash file")
                    store.delete(file)
                    continue
                }
                val createdAt = file.lastModified().takeIf { it > 0 } ?: System.currentTimeMillis()
                queue.insert(listOf(StoredItem(id, CRASH_PRIORITY, createdAt, json)))
                store.delete(file)
                recovered++
            } catch (t: Throwable) {
                // Queue failure: keep the file, try again on the next start, go on with the next file.
                SdkLog.w("recovering a crash file failed", t)
            }
        }
        return recovered
    }

    /**
     * Cheap structural check that does not trust the file: it must parse as a JSON object (android.org.json,
     * always present on a device) with a non-blank `id`, `type == "log"` and a `name`. Returns the item id.
     */
    private fun validatedId(json: String): String? = try {
        val o = JSONObject(json)
        val id = o.optString("id", "")
        if (id.isNotBlank() && o.optString("type") == "log" && o.optString("name").isNotEmpty()) id else null
    } catch (_: Throwable) {
        null
    }
}
