package id.art.tracker.telemetry

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import id.art.tracker.LogLevel
import id.art.tracker.TelemetryItem
import id.art.tracker.core.ItemFactory
import id.art.tracker.core.KeyValueStore
import id.art.tracker.core.SdkLog
import java.io.InputStream

/**
 * Reports how earlier processes of this app ended (ARCHITECTURE.md section 8.1 step 5), from
 * `ActivityManager.getHistoricalProcessExitReasons` (API 30+; below that there is nothing to read):
 * `REASON_ANR` -> `anr` and `REASON_CRASH_NATIVE` -> `native_crash` (both `fatal`, with the trace cut at 16 KB),
 * `REASON_LOW_MEMORY` -> `low_memory_kill` (`warn`). `REASON_CRASH` (a Java crash) is skipped on purpose:
 * [CrashHandler] already covers it, with a better stack trace.
 *
 * Only entries newer than the last processed one are looked at; that timestamp is kept in [store] under
 * [KEY_LAST_TS] and advances over every entry seen, ignored reasons included. Entries of every process of the
 * package are kept (multi-process apps), each tagged with a `process_name` attribute.
 *
 * Attribution: an exit belongs to a PREVIOUS process, so the items carry the CURRENT session id (the old one is
 * unknown), but their `timestamp` is the moment of the exit, which places the event correctly on the timeline.
 *
 * Reads files and the system service: call it off the main thread. Never throws.
 */
internal class ExitInfoCollector(
    private val context: Context,
    private val store: KeyValueStore,
    private val items: ItemFactory,
) {
    /** Builds the items for the exits not seen yet and remembers them as processed. */
    fun collect(): List<TelemetryItem> = process(emit = true)

    /**
     * Marks everything currently known as processed without producing items. Used while the SDK is disabled
     * (opt-out), so the exits of that time are not reported after a later opt-in.
     */
    fun markProcessed() {
        process(emit = false)
    }

    private fun process(emit: Boolean): List<TelemetryItem> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return emptyList()
        return try {
            read(emit)
        } catch (t: Throwable) {
            SdkLog.w("reading the process exit reasons failed", t)
            emptyList()
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun read(emit: Boolean): List<TelemetryItem> {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return emptyList()
        val last = store.getLong(KEY_LAST_TS, 0L)
        val fresh = manager.getHistoricalProcessExitReasons(context.packageName, 0, 0)
            .filter { it.timestamp > last }
            .sortedBy { it.timestamp }
        if (fresh.isEmpty()) return emptyList()

        val result = ArrayList<TelemetryItem>()
        if (emit) {
            for (info in fresh) {
                runCatching { toItem(info) }
                    .onFailure { SdkLog.w("converting an exit reason failed", it) }
                    .getOrNull()
                    ?.let(result::add)
            }
        }
        // Persisted before the caller enqueues: at most once, a kill in between loses the report but never repeats it.
        store.putLong(KEY_LAST_TS, fresh.maxOf { it.timestamp })
        return result
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun toItem(info: ApplicationExitInfo): TelemetryItem? {
        val (name, level) = when (info.reason) {
            ApplicationExitInfo.REASON_ANR -> "anr" to LogLevel.FATAL
            ApplicationExitInfo.REASON_CRASH_NATIVE -> "native_crash" to LogLevel.FATAL
            ApplicationExitInfo.REASON_LOW_MEMORY -> "low_memory_kill" to LogLevel.WARN
            else -> return null
        }
        val attrs = LinkedHashMap<String, Any?>()
        attrs["reason"] = info.reason
        attrs["reason_name"] = name
        info.description?.let { attrs["description"] = it }
        attrs["importance"] = info.importance
        attrs["pss"] = info.pss
        attrs["rss"] = info.rss
        attrs["exit_timestamp"] = info.timestamp
        info.processName?.let { attrs["process_name"] = it }
        if (level == LogLevel.FATAL) readTrace(info)?.let { attrs["trace"] = it }
        // Built by the factory for the id and the session, then moved to the time of the exit.
        return items.log(level, name, info.description?.takeIf { it.isNotBlank() }, attrs)
            .copy(timestampMs = info.timestamp)
    }

    /** First [MAX_TRACE_BYTES] of the trace as lossy UTF-8; null when absent or unreadable. The stream is always closed. */
    @RequiresApi(Build.VERSION_CODES.R)
    private fun readTrace(info: ApplicationExitInfo): String? {
        var stream: InputStream? = null
        return try {
            stream = info.traceInputStream ?: return null
            val buffer = ByteArray(MAX_TRACE_BYTES)
            var length = 0
            while (length < buffer.size) {
                val n = stream.read(buffer, length, buffer.size - length)
                if (n < 0) break
                length += n
            }
            if (length == 0) return null
            val text = String(buffer, 0, length, Charsets.UTF_8)
            // A cut inside a multi-byte character decodes to a trailing U+FFFD: drop that artefact.
            if (length == buffer.size && text.endsWith('�')) text.dropLast(1) else text
        } catch (t: Throwable) {
            SdkLog.w("reading the exit trace failed", t)
            null
        } finally {
            runCatching { stream?.close() }
        }
    }

    internal companion object {
        const val KEY_LAST_TS = "exitinfo_last_ts"
        const val MAX_TRACE_BYTES = 16 * 1024
    }
}
