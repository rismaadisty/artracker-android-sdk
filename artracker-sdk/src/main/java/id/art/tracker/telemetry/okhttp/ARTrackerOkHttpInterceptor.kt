package id.art.tracker.telemetry.okhttp

import id.art.tracker.core.Clock
import id.art.tracker.core.SdkLog
import id.art.tracker.privacy.UrlScrubber
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response

/**
 * Records every HTTP call as an `http_request` log item (plus an `http` breadcrumb). Add it as an
 * *application* interceptor: `OkHttpClient.Builder().addInterceptor(ARTrackerOkHttpInterceptor())`.
 *
 * Guarantees to the host app:
 *  - the response is returned untouched and its body is never read (`res_bytes` comes from `Content-Length`,
 *    so it is absent for chunked or transparently decompressed responses);
 *  - an exception from the rest of the chain is reported and then rethrown as the very same instance;
 *  - nothing the SDK does here can throw into the request, and nothing blocks (the report only enqueues);
 *  - before `ARTracker.init`, or while the SDK is disabled, it only measures and drops the result.
 *
 * Privacy: the URL is stripped of credentials and sensitive query parameters before it is handed on. Request and
 * response headers are never captured: `NetworkConfig.captureHeaders` is reserved and currently ignored.
 *
 * `okhttp3` is a `compileOnly` dependency of the SDK, so this class is only loaded when the host adds it; no other
 * SDK class refers to it or to OkHttp types.
 */
public class ARTrackerOkHttpInterceptor internal constructor(
    private val reporter: HttpReporter,
    private val clock: Clock,
) : Interceptor {

    public constructor() : this(CoreHttpReporter(), MONOTONIC)

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        // The SDK's own delivery (ARTrackerOkHttpSender): reporting it would feed itself forever.
        if (request.tag(SdkRequestTag::class.java) != null) return chain.proceed(request)
        val startMs = safeNow()
        val response = try {
            chain.proceed(request)
        } catch (t: Throwable) {
            report(request, null, startMs, t)
            throw t
        }
        report(request, response, startMs, null)
        return response
    }

    private fun report(request: Request, response: Response?, startMs: Long, error: Throwable?) {
        try {
            val url = request.url
            val record = HttpRecord(
                method = request.method,
                url = UrlScrubber.scrub(url.newBuilder().username("").password("").build().toString()),
                host = url.host,
                status = response?.code,
                durationMs = (safeNow() - startMs).coerceAtLeast(0),
                requestBytes = request.body?.contentLength()?.takeIf { it >= 0 },
                responseBytes = response?.body?.contentLength()?.takeIf { it >= 0 },
                protocol = response?.protocol?.toString(),
                error = error?.let(CoreHttpReporter::describeError),
            )
            reporter.report(record)
        } catch (t: Throwable) {
            SdkLog.w("recording an http request failed", t)
        }
    }

    private fun safeNow(): Long = try {
        clock.nowMs()
    } catch (_: Throwable) {
        0L
    }

    private companion object {
        /** Wall-clock time can jump; durations use the monotonic clock. Only differences are meaningful. */
        val MONOTONIC: Clock = Clock { System.nanoTime() / 1_000_000 }
    }
}
