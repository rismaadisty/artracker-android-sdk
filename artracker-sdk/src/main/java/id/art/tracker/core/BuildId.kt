package id.art.tracker.core

import android.content.Context
import android.content.pm.PackageManager
import id.art.tracker.privacy.AttributeRedactor

/**
 * The build id the host puts in its manifest, `<meta-data android:name="id.art.tracker.BUILD_ID" android:value="..."/>`,
 * so the backend can pick the R8 mapping that belongs to a stack trace. It is the only source (no `ARTrackerConfig`
 * parameter: adding one breaks the Kotlin default-arguments constructor for compiled hosts).
 *
 * Android stores a numeric `android:value` as an `Integer` (`4512`) and a value like `12e45` as a `Float`, so the value is
 * normalised: a string is trimmed, an integer becomes its decimal string, anything else is ignored (with one warning),
 * a blank value is no value, and the result is cut to [MAX_LENGTH] characters without splitting a surrogate pair.
 *
 * It must also reach the backend unchanged: every attribute value goes through the redactor, which masks card-like,
 * phone-like and national-id-like digit runs. An id that the redactor would rewrite (`build-20261116093000`, now and
 * then a UUID or a long hex sha) is dropped with a warning instead of being sent as `**** **** **** 1234`, which would
 * match no mapping: better no id than a wrong one.
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
        val clipped = if (text.length <= MAX_LENGTH) {
            text
        } else {
            val end = if (Character.isHighSurrogate(text[MAX_LENGTH - 1])) MAX_LENGTH - 1 else MAX_LENGTH
            text.substring(0, end)
        }
        if (AttributeRedactor.redactString(clipped) != clipped) {
            SdkLog.w("$META_DATA_KEY looks like personal data to the redactor (long digit runs): not sent; add a letter prefix such as git-")
            return null
        }
        return clipped
    }
}
