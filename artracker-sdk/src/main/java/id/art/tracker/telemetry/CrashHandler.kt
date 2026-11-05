package id.art.tracker.telemetry

import id.art.tracker.core.Iso8601
import id.art.tracker.core.Json
import id.art.tracker.core.UuidV7
import id.art.tracker.privacy.AttributeRedactor
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Default uncaught-exception handler (ARCHITECTURE.md section 8.1). While the process is dying it writes the crash
 * item to [store] synchronously and then ALWAYS hands the exception to the handler that was installed before
 * (Crashlytics and the like, or the platform's), exactly once:
 *
 * - everything we do is inside `try/catch (Throwable)`: a failing [buildItemJson], a full disk or an
 *   `OutOfMemoryError` only means no file, never a missing delegate call;
 * - re-entrancy guard: while one crash is being written, another uncaught exception (a crash in our own code
 *   that escaped, or a second thread dying at the same time) is delegated at once, without a file;
 * - no coroutines, database, network or lock acquired here.
 *
 * [buildItemJson] returns `(id, finalItemJson)`; [shouldCapture] is consulted first (opt-out).
 */
internal class CrashHandler(
    private val previous: Thread.UncaughtExceptionHandler?,
    private val store: CrashStore,
    private val buildItemJson: (Thread, Throwable) -> Pair<String, String>,
    private val shouldCapture: () -> Boolean = { true },
) : Thread.UncaughtExceptionHandler {
    private val busy = AtomicBoolean(false)

    /** False once [uninstall] ran: a handler somebody chained to us then only delegates. */
    @Volatile
    private var active = true

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        if (active && busy.compareAndSet(false, true)) {
            try {
                capture(thread, throwable)
            } finally {
                busy.set(false)
            }
        }
        delegate(thread, throwable)
    }

    private fun capture(thread: Thread, throwable: Throwable) {
        try {
            if (!shouldCapture()) return
            val (id, json) = buildItemJson(thread, throwable)
            store.writeSync(id, json)
        } catch (_: Throwable) {
            // Nothing may stop the previous handler from running.
        }
    }

    private fun delegate(thread: Thread, throwable: Throwable) {
        try {
            if (previous != null) {
                previous.uncaughtException(thread, throwable)
            } else {
                // No handler before us. Do NOT go through `thread.threadGroup.uncaughtException`: it dispatches to the
                // default handler, which is this one, and would loop. Print the trace like the JVM does (on Android
                // System.err ends up in logcat) and let the process die normally.
                System.err.println("Exception in thread \"${thread.name}\"")
                throwable.printStackTrace()
            }
        } catch (_: Throwable) {
            // The runtime ignores exceptions thrown by an uncaught-exception handler as well.
        }
    }

    /**
     * Puts the previous handler back, but only if we are still the default one; when somebody installed theirs
     * after us they hold a reference to us, so we merely turn into a pass-through.
     */
    fun uninstall() {
        active = false
        if (Thread.getDefaultUncaughtExceptionHandler() === this) {
            Thread.setDefaultUncaughtExceptionHandler(previous)
        }
    }

    companion object {
        /**
         * Installs a handler as the process default and chains to the current default. If the current default is
         * a leftover [CrashHandler] of an earlier SDK instance, that one is replaced rather than chained to, so
         * a crash is never written twice.
         */
        fun install(
            store: CrashStore,
            buildItemJson: (Thread, Throwable) -> Pair<String, String>,
            shouldCapture: () -> Boolean = { true },
        ): CrashHandler {
            var prev = Thread.getDefaultUncaughtExceptionHandler()
            while (prev is CrashHandler) {
                prev.active = false
                prev = prev.previous
            }
            val handler = CrashHandler(prev, store, buildItemJson, shouldCapture)
            Thread.setDefaultUncaughtExceptionHandler(handler)
            return handler
        }
    }
}

/**
 * Builds the final JSON of a `log`/`fatal`/`crash` item (same shape as `TelemetryItem.toJson()`), by hand and
 * without coroutines, database or network, because it runs on a dying process. The exception message, the stack
 * trace header lines and the breadcrumbs are redacted here since nothing downstream will touch this JSON.
 * Work is budgeted: breadcrumbs beyond [BUDGET_NANOS] are dropped rather than redacted late.
 */
internal object CrashItemBuilder {
    const val BUDGET_NANOS = 40_000_000L
    private const val MAX_MESSAGE = 4096
    private const val MAX_ID = 128
    private const val MAX_ATTRIBUTES_BYTES = 24 * 1024
    private const val ATTRIBUTE_SLACK_BYTES = 512
    private const val REDUCED_STACK_BYTES = 8 * 1024

    fun build(
        thread: Thread,
        t: Throwable,
        nowMs: Long,
        sessionId: String?,
        userId: String?,
        appVersion: String?,
        screen: String?,
        breadcrumbs: Breadcrumbs,
    ): Pair<String, String> {
        val startNs = System.nanoTime()
        val id = UuidV7.generate(nowMs)
        val summary = Stacktrace.summary(t).let { if (it.length > MAX_MESSAGE) it.substring(0, MAX_MESSAGE) else it }
        val message = AttributeRedactor.redactString(summary)

        val attrs = LinkedHashMap<String, Any?>(12)
        attrs["thread"] = clip(thread.name ?: "unknown", MAX_ID)
        attrs["exception_class"] = t.javaClass.name
        attrs["stacktrace"] = redactStack(Stacktrace.format(t))
        attrs["fingerprint"] = Stacktrace.fingerprint(t)
        attrs["handled"] = false
        attrs["breadcrumbs"] = redactedBreadcrumbs(breadcrumbs, startNs)
        if (screen != null) attrs["screen"] = AttributeRedactor.redactString(screen)
        attrs["app_version"] = appVersion ?: "unknown"
        attrs["mem_free_bytes"] = Runtime.getRuntime().freeMemory()

        // The server drops attributes above 24 KB: shed the breadcrumbs first, then half of the stack trace.
        var attributesJson = Json.encode(attrs)
        if (Stacktrace.utf8Size(attributesJson) > MAX_ATTRIBUTES_BYTES - ATTRIBUTE_SLACK_BYTES) {
            attrs["breadcrumbs"] = emptyList<Any?>()
            attributesJson = Json.encode(attrs)
        }
        if (Stacktrace.utf8Size(attributesJson) > MAX_ATTRIBUTES_BYTES - ATTRIBUTE_SLACK_BYTES) {
            attrs["stacktrace"] = redactStack(Stacktrace.format(t, REDUCED_STACK_BYTES))
            attributesJson = Json.encode(attrs)
        }

        val sb = StringBuilder(attributesJson.length + 512)
        sb.append("{\"id\":").append(Json.encode(id))
        sb.append(",\"type\":\"log\",\"name\":\"crash\",\"level\":\"fatal\"")
        sb.append(",\"message\":").append(Json.encode(message))
        sb.append(",\"timestamp\":").append(Json.encode(Iso8601.format(nowMs)))
        sb.append(",\"session_id\":").append(Json.encode(clip(sessionId ?: "unknown", MAX_ID)))
        if (userId != null) sb.append(",\"user_id\":").append(Json.encode(clip(userId, MAX_ID)))
        sb.append(",\"attributes\":").append(attributesJson)
        sb.append('}')
        return id to sb.toString()
    }

    /** Header lines (`Class: message`, `Caused by: ...`) can carry personal data; `\tat` frames never do. */
    private fun redactStack(stack: String): String {
        val out = StringBuilder(stack.length)
        var start = 0
        while (start <= stack.length) {
            var end = stack.indexOf('\n', start)
            if (end < 0) end = stack.length
            val line = stack.substring(start, end)
            out.append(if (line.startsWith("\tat ")) line else AttributeRedactor.redactString(line))
            if (end < stack.length) out.append('\n')
            start = end + 1
        }
        return out.toString()
    }

    private fun redactedBreadcrumbs(breadcrumbs: Breadcrumbs, startNs: Long): List<Map<String, Any?>> {
        val out = ArrayList<Map<String, Any?>>(Breadcrumbs.DEFAULT_CAPACITY)
        // Newest first so that when the budget runs out the entries closest to the crash are the ones kept.
        for (b in breadcrumbs.snapshot().asReversed()) {
            if (System.nanoTime() - startNs > BUDGET_NANOS) break
            val m = LinkedHashMap<String, Any?>(4)
            m["ts"] = b["ts"]
            m["type"] = b["type"]
            m["message"] = AttributeRedactor.redactString(b["message"] as? String ?: "")
            val data = b["data"] as? Map<*, *>
            if (!data.isNullOrEmpty()) {
                val d = LinkedHashMap<String, Any?>(data.size)
                for ((k, v) in data) {
                    val key = k.toString()
                    d[key] = if (v is String) AttributeRedactor.redactString(v) else v
                }
                m["data"] = d
            }
            out += m
        }
        out.reverse()
        return out
    }

    private fun clip(s: String, max: Int): String = if (s.length <= max) s else s.substring(0, max)
}
