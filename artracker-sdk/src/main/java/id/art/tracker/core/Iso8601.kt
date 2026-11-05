package id.art.tracker.core

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
            }
    }

    fun format(epochMs: Long): String = formatter.get()!!.format(Date(epochMs))
}
