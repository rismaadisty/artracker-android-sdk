package id.art.tracker.playground.labs

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

interface NoteStore {
    var pending: String?
}

/** `commit()`: the process dies right after the note is written. */
class SharedPrefsNoteStore(context: Context) : NoteStore {
    private val prefs = context.applicationContext.getSharedPreferences("playground_crash_note", Context.MODE_PRIVATE)

    override var pending: String?
        get() = prefs.getString("pending", null)
        set(value) {
            prefs.edit().apply { if (value == null) remove("pending") else putString("pending", value) }.commit()
        }
}

/**
 * Crash items bypass `beforeSend` and are only sent after the next launch, so the lab leaves a note before it crashes
 * the app and shows it once after the relaunch.
 */
class CrashNote(private val store: NoteStore, private val nowMs: () -> Long = System::currentTimeMillis) {
    fun recordBeforeCrash(kind: String) {
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        store.pending = "$kind · ${fmt.format(Date(nowMs()))} UTC"
    }

    /** The note once, then null. */
    fun consume(): String? = store.pending.also { store.pending = null }
}
