package id.art.tracker.transport

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import id.art.tracker.core.ARTrackerCore
import id.art.tracker.core.Clock
import id.art.tracker.core.KillSwitch
import id.art.tracker.core.SdkLog
import id.art.tracker.core.SharedPrefsStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Holder for the live [Flusher] of this process. Core (`ARTrackerCore`) sets it on init so the worker reuses the
 * same lock, backoff state and queue instance instead of racing a second one.
 */
internal object FlusherRegistry {
    @Volatile
    var current: Flusher? = null

    /** The live [ReplayFlusher]; set when the core first needs the replay queue, so absent if replay was never used. */
    @Volatile
    var replay: ReplayFlusher? = null
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
            try {
                val config = TransportConfigStore(SharedPrefsStore(applicationContext, TransportConfigStore.PREFS_NAME)).load()
                val registered = FlusherRegistry.current
                val ingest = if (registered != null) registered.flushAll() else config?.let { runStandalone(it) }
                val replay = flushReplay(config)
                result(ingest, replay)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // Flusher rethrows Errors (OutOfMemoryError, NoClassDefFoundError, ...) after releasing its batch.
                // Let WorkManager retry with its own backoff instead of failing the work or crashing the process.
                SdkLog.w("background flush failed (${e.javaClass.simpleName})")
                Result.retry()
            }
        }
    }

    /** Null when there is nothing to do (no saved configuration, or the event store cannot be opened). */
    private suspend fun runStandalone(config: TransportConfigStore.Saved): FlushOutcome? {
        val clock = Clock.SYSTEM
        // Deliberately no resetOrphanedBatches(): that is for SDK init only. In a process without init() another
        // process-wide store may hold in-flight batches that must not be handed out twice.
        val store = try {
            EventStore(applicationContext, clock)
        } catch (e: Exception) {
            SdkLog.w("opening event store failed", e)
            return null
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
            return flusher.flushAll()
        } finally {
            store.close()
        }
    }

    /**
     * Uploads queued replay batches. The live process's [ReplayFlusher] is reused; in a fresh process one is built from
     * the saved configuration, but only when a replay database exists (opening one would create it). Null when there
     * is nothing to upload.
     */
    private suspend fun flushReplay(config: TransportConfigStore.Saved?): FlushOutcome? {
        FlusherRegistry.replay?.let { return it.flushAll() }
        // A live core owns the replay queue and uploads it itself: a second flusher beside it would double the uploads in
        // flight and make two SQLite connections compete for the write lock.
        if (ARTrackerCore.instanceOrNull() != null) return null
        config ?: return null
        if (!applicationContext.getDatabasePath(ReplayStore.DB_NAME).exists()) return null
        val clock = Clock.SYSTEM
        // Like the ingest path: no resetOrphaned(), another process-wide store may hold batches in flight.
        val store = try {
            ReplayStore(applicationContext, clock)
        } catch (e: Exception) {
            SdkLog.w("opening replay store failed", e)
            return null
        }
        try {
            val flusher = ReplayFlusher(
                queue = store,
                sender = ReplayClient(config.endpoint, config.apiKey),
                killSwitch = KillSwitch(SharedPrefsStore(applicationContext, TransportConfigStore.PREFS_NAME), clock),
                clock = clock,
                backoff = Backoff(),
                scheduler = BackgroundScheduler { },
            )
            return flusher.flushAll()
        } finally {
            store.close()
        }
    }

    /** The worst outcome of the two queues decides: a failed or paused one is retried by WorkManager. */
    private fun result(vararg outcomes: FlushOutcome?): Result =
        if (outcomes.any { it == FlushOutcome.FAILED || it == FlushOutcome.PAUSED }) Result.retry() else Result.success()
}
