package id.art.tracker.transport

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import id.art.tracker.core.Clock
import id.art.tracker.core.KillSwitch
import id.art.tracker.core.SdkLog
import id.art.tracker.core.SharedPrefsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Holder for the live [Flusher] of this process. Core (`ARTrackerCore`) sets it on init so the worker reuses the
 * same lock, backoff state and queue instance instead of racing a second one.
 */
internal object FlusherRegistry {
    @Volatile
    var current: Flusher? = null
}

/**
 * Background flush (ARCHITECTURE.md section 9.4). May run in a process that WorkManager just spawned, where
 * `init()` never ran: then the [Flusher] is built from [TransportConfigStore]. With no saved configuration there
 * is nothing to do and the work succeeds.
 */
internal class FlushWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        // Flusher blocks its thread on HTTP, so never run it on the default (CPU) dispatcher.
        return withContext(Dispatchers.IO) {
            val registered = FlusherRegistry.current
            if (registered != null) {
                flush(registered)
            } else {
                runStandalone()
            }
        }
    }

    private suspend fun runStandalone(): Result {
        val config = TransportConfigStore(SharedPrefsStore(applicationContext, TransportConfigStore.PREFS_NAME)).load()
            ?: return Result.success()
        val clock = Clock.SYSTEM
        // Deliberately no resetOrphanedBatches(): that is for SDK init only. In a process without init() another
        // process-wide store may hold in-flight batches that must not be handed out twice.
        val store = try {
            EventStore(applicationContext, clock)
        } catch (e: Exception) {
            SdkLog.w("opening event store failed", e)
            return Result.success()
        }
        try {
            val killSwitch = KillSwitch(SharedPrefsStore(applicationContext, TransportConfigStore.PREFS_NAME), clock)
            val encoder = PayloadEncoder {
                mapOf(
                    "environment" to config.environment,
                    "app_version" to config.appVersion,
                    "sdk_platform" to "android",
                )
            }
            val flusher = Flusher(
                queue = store,
                encoder = encoder,
                sender = IngestClient(config.endpoint, config.apiKey),
                killSwitch = killSwitch,
                clock = clock,
                backoff = Backoff(),
                // We are the background scheduler: a failure is retried through Result.retry() below.
                scheduler = BackgroundScheduler { },
            )
            return flush(flusher)
        } finally {
            store.close()
        }
    }

    private suspend fun flush(flusher: Flusher): Result =
        when (flusher.flushAll()) {
            FlushOutcome.FAILED, FlushOutcome.PAUSED -> Result.retry()
            FlushOutcome.EMPTY, FlushOutcome.SENT, FlushOutcome.DROPPED, FlushOutcome.DISABLED -> Result.success()
        }
}
