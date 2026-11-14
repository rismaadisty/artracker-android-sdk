package id.art.tracker.core

import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

internal object Iso8601 {
    // SimpleDateFormat is not thread-safe, and java.time needs desugaring on API 24.
    private val formatter = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue(): SimpleDateFormat =
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
                isLenient = false
            }
    }

    fun format(epochMs: Long): String = formatter.get()!!.format(Date(epochMs))

    /** Inverse of [format]; null when [text] is not exactly in that form. */
    fun parse(text: String): Long? = runCatching {
        val position = ParsePosition(0)
        val date = formatter.get()!!.parse(text, position)
        if (date == null || position.index != text.length) null else date.time
    }.getOrNull()
}
