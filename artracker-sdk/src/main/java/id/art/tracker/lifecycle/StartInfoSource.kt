package id.art.tracker.lifecycle

import android.app.ActivityManager
import android.app.Application
import android.app.ApplicationStartInfo
import android.os.Handler
import androidx.annotation.RequiresApi
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.function.Consumer

/** Where the platform's own start timestamps come from; a fake in tests. */
internal fun interface StartInfoSource {
    /** Calls [onResult] once, on any thread, with the timestamps or null (unsupported, timeout 2 000 ms, error). */
    fun request(onResult: (StartupTimestamps?) -> Unit)
}

/** Below API 35 there is nothing to ask. */
internal object NoStartInfo : StartInfoSource {
    override fun request(onResult: (StartupTimestamps?) -> Unit) = onResult(null)
}

private const val START_INFO_TIMEOUT_MS = 2_000L
private const val NANOS_PER_MS = 1_000_000L

/**
 * API 35+: `ActivityManager.addApplicationStartInfoCompletionListener`. Of its timestamps, `FORK`, `BIND_APPLICATION`
 * and `APPLICATION_ONCREATE` (nanoseconds on one clock) become [StartupTimestamps]; only their differences are used.
 * It answers null when the system does not answer within [START_INFO_TIMEOUT_MS] or has no fork time.
 */
@RequiresApi(35)
internal class ApplicationStartInfoSource(
    private val app: Application,
    private val mainHandler: Handler,
) : StartInfoSource {
    override fun request(onResult: (StartupTimestamps?) -> Unit) {
        val manager = runCatching { app.getSystemService(ActivityManager::class.java) }.getOrNull()
        if (manager == null) return onResult(null)
        val done = AtomicBoolean(false)
        val listenerRef = AtomicReference<Consumer<ApplicationStartInfo>?>(null)
        val timeoutRef = AtomicReference<Runnable?>(null)

        fun complete(result: StartupTimestamps?) {
            if (!done.compareAndSet(false, true)) return
            timeoutRef.get()?.let(mainHandler::removeCallbacks)
            listenerRef.get()?.let { runCatching { manager.removeApplicationStartInfoCompletionListener(it) } }
            onResult(result)
        }

        val timeout = Runnable { complete(null) }
        val listener = Consumer<ApplicationStartInfo> { info -> complete(runCatching { timestampsOf(info) }.getOrNull()) }
        timeoutRef.set(timeout)
        listenerRef.set(listener)
        try {
            mainHandler.postDelayed(timeout, START_INFO_TIMEOUT_MS)
            manager.addApplicationStartInfoCompletionListener({ it.run() }, listener)
        } catch (t: Throwable) {
            complete(null)
        }
    }

    private fun timestampsOf(info: ApplicationStartInfo): StartupTimestamps? {
        val map = info.startupTimestamps
        val forkNs = map[ApplicationStartInfo.START_TIMESTAMP_FORK] ?: return null
        return StartupTimestamps(
            forkMs = forkNs / NANOS_PER_MS,
            bindApplicationMs = map[ApplicationStartInfo.START_TIMESTAMP_BIND_APPLICATION]?.let { it / NANOS_PER_MS },
            applicationOnCreateMs = map[ApplicationStartInfo.START_TIMESTAMP_APPLICATION_ONCREATE]?.let { it / NANOS_PER_MS },
        )
    }
}
