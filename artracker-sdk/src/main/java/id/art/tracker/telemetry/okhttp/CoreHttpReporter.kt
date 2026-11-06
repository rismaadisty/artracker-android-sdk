package id.art.tracker.telemetry.okhttp

import id.art.tracker.core.ARTrackerCore
import id.art.tracker.core.SdkLog
import id.art.tracker.privacy.AttributeRedactor
import id.art.tracker.privacy.UrlScrubber
import java.net.URI
import java.util.Locale
import java.util.concurrent.ThreadLocalRandom

/**
 * Default [HttpReporter]: applies the `NetworkConfig` policy and hands the result to the running core.
 *
 * Does nothing when the SDK is not initialized or is disabled (opt-out / 401 kill-switch). Requests to an
 * ignored host and to the SDK's own endpoint host are dropped entirely (no item, no breadcrumb). Every other
 * request leaves an `http` breadcrumb, even when sampling or `captureOnlyErrors` keeps it out of the queue:
 * breadcrumbs are never sent on their own, only attached to a later crash or error.
 *
 * Never throws and never blocks: it only builds a small item and calls the lock-free `enqueue`.
 */
internal class CoreHttpReporter(
    /** Uniform in `[0, 1)`; injectable so tests are deterministic. */
    private val random: () -> Double = { ThreadLocalRandom.current().nextDouble() },
) : HttpReporter {

    override fun report(record: HttpRecord) {
        try {
            val core = ARTrackerCore.instanceOrNull() ?: return
            if (core.isDisabled()) return
            val network = core.config.network
            val host = record.host.lowercase(Locale.ROOT)
            if (network.ignoreHosts.any { it.lowercase(Locale.ROOT) == host }) return
            if (host == endpointHost(core.config.endpoint)) return

            val message = "${record.method} ${pathOf(record.url)} -> ${record.status ?: "error"}"
            val breadcrumbData = LinkedHashMap<String, Any?>(3)
            record.status?.let { breadcrumbData["status"] = it }
            breadcrumbData["duration_ms"] = record.durationMs
            core.breadcrumbs.add("http", message, breadcrumbData)

            val isError = record.status == null || record.status >= 400
            if (network.captureOnlyErrors && !isError) return
            if (!sampled(network.sampleRate)) return

            val attrs = LinkedHashMap<String, Any?>(10)
            attrs["method"] = record.method
            attrs["url"] = UrlScrubber.scrub(record.url)
            attrs["host"] = record.host
            record.status?.let { attrs["status"] = it }
            attrs["duration_ms"] = record.durationMs
            record.requestBytes?.let { attrs["req_bytes"] = it }
            record.responseBytes?.let { attrs["res_bytes"] = it }
            record.protocol?.let { attrs["protocol"] = it }
            record.error?.let { attrs["error"] = sanitizeError(it) }
            core.pipeline.enqueue(core.items.log(record.level(), "http_request", message, attrs))
        } catch (t: Throwable) {
            SdkLog.w("reporting an http request failed", t)
        }
    }

    private fun sampled(rate: Double): Boolean = rate >= 1.0 || (rate > 0.0 && random() < rate)

    private fun endpointHost(endpoint: String): String? =
        runCatching { URI(endpoint).host?.lowercase(Locale.ROOT) }.getOrNull()

    internal companion object {
        private const val MAX_ERROR = 200

        /** `ExceptionClass: message`, redacted and clipped so a long or sensitive message cannot leak or bloat. */
        fun describeError(t: Throwable): String {
            val message = runCatching { t.message }.getOrNull()
            return sanitizeError(if (message.isNullOrBlank()) t.javaClass.name else "${t.javaClass.name}: $message")
        }

        fun sanitizeError(raw: String): String {
            val redacted = AttributeRedactor.redactString(raw)
            if (redacted.length <= MAX_ERROR) return redacted
            val end = if (Character.isHighSurrogate(redacted[MAX_ERROR - 1])) MAX_ERROR - 1 else MAX_ERROR
            return redacted.substring(0, end)
        }

        /** Path of an absolute URL, without query or fragment (`/` when there is none). */
        fun pathOf(url: String): String {
            val schemeEnd = url.indexOf("://")
            val authorityStart = if (schemeEnd < 0) 0 else schemeEnd + 3
            val pathStart = url.indexOf('/', authorityStart)
            if (pathStart < 0) return "/"
            var end = url.length
            for (c in "?#") {
                val i = url.indexOf(c, pathStart)
                if (i in 0 until end) end = i
            }
            return AttributeRedactor.redactString(UrlScrubber.scrub(url.substring(pathStart, end)))
        }
    }
}
