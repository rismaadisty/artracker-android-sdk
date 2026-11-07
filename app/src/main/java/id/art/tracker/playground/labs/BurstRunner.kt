package id.art.tracker.playground.labs

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.yield

/** Emits `0 until count` through [emit], cooperatively: it checks for cancellation on every item and yields every 100. */
class BurstRunner(private val emit: (Int) -> Unit) {
    suspend fun run(count: Int, onProgress: (done: Int) -> Unit) {
        for (i in 0 until count) {
            currentCoroutineContext().ensureActive()
            emit(i)
            if ((i + 1) % YIELD_EVERY == 0) {
                onProgress(i + 1)
                yield()
            }
        }
        if (count % YIELD_EVERY != 0 || count == 0) onProgress(count)
    }

    private companion object {
        const val YIELD_EVERY = 100
    }
}
