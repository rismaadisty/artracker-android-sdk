package id.art.tracker.transport

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import id.art.tracker.core.SdkLog
import java.util.concurrent.TimeUnit

/**
 * Schedules a single deferred [FlushWorker] that runs once the network is available. Unique work with
 * [ExistingWorkPolicy.KEEP] makes repeated calls idempotent. No periodic work (ARCHITECTURE.md section 9.4).
 */
internal class WorkManagerScheduler(context: Context) : BackgroundScheduler {
    private val appContext: Context = context.applicationContext ?: context

    override fun scheduleFlush() {
        try {
            val request = OneTimeWorkRequestBuilder<FlushWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(appContext).enqueueUniqueWork(UNIQUE_WORK_NAME, ExistingWorkPolicy.KEEP, request)
        } catch (e: Exception) {
            // e.g. WorkManager not initialised: scheduling is best-effort, the foreground timer still retries.
            SdkLog.w("scheduling background flush failed", e)
        }
    }

    companion object {
        const val UNIQUE_WORK_NAME = "artracker-flush"
        const val BACKOFF_SECONDS = 30L
    }
}
