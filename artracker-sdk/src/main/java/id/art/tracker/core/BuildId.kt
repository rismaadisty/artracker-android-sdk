package id.art.tracker.core

import android.content.Context
import android.content.pm.PackageManager

/**
 * The build id the host puts in its manifest, `<meta-data android:name="id.art.tracker.BUILD_ID" android:value="..."/>`,
 * so the backend can pick the R8 mapping that belongs to a stack trace. It is the only source (no `ARTrackerConfig`
 * parameter: adding one breaks the Kotlin default-arguments constructor for compiled hosts).
 *
 * Android stores a numeric `android:value` as an `Integer` (`4512`) and a value like `12e45` as a `Float`, so the value is
 * normalised: a string is trimmed, an integer becomes its decimal string, anything else is ignored (with one warning),
 * a blank value is no value, and the result is cut to [MAX_LENGTH] characters without splitting a surrogate pair.
 */
internal object BuildId {
    const val META_DATA_KEY = "id.art.tracker.BUILD_ID"
    const val MAX_LENGTH = 128

    @Volatile
    private var warned = false

    /** Reads the manifest value. Never throws; talks to the PackageManager, so never on the main thread. */
    fun read(context: Context): String? = runCatching {
        val app = context.applicationContext ?: context
        @Suppress("DEPRECATION") // Bundle.get is the only way to read a value of any type
        val raw = app.packageManager.getApplicationInfo(app.packageName, PackageManager.GET_META_DATA).metaData?.get(META_DATA_KEY)
        normalize(raw)
    }.getOrNull()

    fun normalize(raw: Any?): String? {
        val text = when (raw) {
            null -> return null
            is String -> raw.trim()
            is Int -> raw.toString()
            is Long -> raw.toString()
            else -> {
                if (!warned) {
                    warned = true
                    SdkLog.w("$META_DATA_KEY is a ${raw.javaClass.simpleName}, not text or an integer: ignored (write it so it contains a letter, such as git-<sha>)")
                }
                return null
            }
        }
        if (text.isEmpty()) return null
        if (text.length <= MAX_LENGTH) return text
        val end = if (Character.isHighSurrogate(text[MAX_LENGTH - 1])) MAX_LENGTH - 1 else MAX_LENGTH
        return text.substring(0, end)
    }
}
