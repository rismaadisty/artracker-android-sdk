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
    /**
     * For each pending file: validate, hand its JSON to [process] (the event pipeline's `beforeSend` + redaction step,
     * which returns the item to store or null to drop it), insert the result into [queue], and delete the file only
     * when `insert` returned true (stored, or already stored under that id) or [process] dropped it on purpose. On
     * false or an exception (also from [process]), or a kill in between, the file stays for the next start.
     * A file that is unreadable or not a valid crash item is deleted without being inserted, so one poison
     * file can neither be retried forever nor spoil the others. Returns the number of items that are now in the queue.
     */
    fun recover(store: CrashStore, queue: ItemQueue, process: (String) -> StoredItem?): Int {
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
                val stored = process(json)
                if (stored == null) {
                    // Dropped by beforeSend (or unserialisable): the user's hook decided, nothing is kept.
                    store.delete(file)
                    continue
                }
                if (queue.insert(listOf(stored))) {
                    store.delete(file)
                    recovered++
                } else {
                    // The queue could not store it (the real EventStore reports this as false, it never throws):
                    // the file is the only copy, so it stays for the next start.
                    SdkLog.w("the queue could not store a crash item; keeping its file")
                }
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
