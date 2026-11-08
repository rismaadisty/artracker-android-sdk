package id.art.tracker.transport

/** Schedules a deferred flush (WorkManager in production) for when the network is back. */
internal fun interface BackgroundScheduler {
    fun scheduleFlush()
}
