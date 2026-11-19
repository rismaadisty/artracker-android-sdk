package id.art.tracker.appstart

import id.art.tracker.core.KillSwitch
import id.art.tracker.core.SdkLog
import id.art.tracker.transport.FlushOutcome
import id.art.tracker.transport.SendResult
import id.art.tracker.transport.Sender
import java.io.IOException

/**
 * Uploads the pending startup profiles through [sender], one at a time, oldest first, never from two threads at once.
 * Blocks on HTTP and file I/O: never call it on the main thread.
 *
 * - accepted, dropped (4xx) and too large (413): the file is deleted and the next one goes on;
 * - 401 / 403: stops and keeps the file; the ingest flusher owns the kill-switch trip;
 * - `Retry-After`, a network error: counts one failed attempt on that file and stops.
 */
internal class ProfileUploader(
    private val store: ProfileStore,
    private val sender: Sender,
    private val killSwitch: KillSwitch,
) {
    private val lock = Any()

    /**
     * [FlushOutcome.EMPTY] nothing pending; [FlushOutcome.SENT] at least one was accepted; [FlushOutcome.DROPPED] only
     * rejected files were removed; [FlushOutcome.FAILED] stopped on a retryable failure; [FlushOutcome.DISABLED] the
     * kill-switch is active, or the key was refused (nothing more can be sent until it is fixed).
     */
    fun uploadAll(): FlushOutcome = synchronized(lock) {
        if (killSwitch.isActive()) return FlushOutcome.DISABLED
        val pending = store.pending()
        if (pending.isEmpty()) return FlushOutcome.EMPTY
        var accepted = false
        for (p in pending) {
            if (killSwitch.isActive()) return FlushOutcome.DISABLED
            val body = try {
                p.file.readBytes()
            } catch (e: IOException) {
                SdkLog.w("app start profile unreadable, deleting it", e)
                store.delete(p)
                continue
            }
            when (val result = sender.send(body)) {
                SendResult.Accepted -> {
                    store.delete(p)
                    accepted = true
                }
                is SendResult.Drop, SendResult.TooLarge -> {
                    SdkLog.d("app start profile rejected by the server ($result), dropped")
                    store.delete(p)
                }
                SendResult.Unauthorized -> return FlushOutcome.DISABLED
                is SendResult.RetryAfter, is SendResult.NetworkError -> {
                    store.markFailed(p)
                    return FlushOutcome.FAILED
                }
            }
        }
        if (accepted) FlushOutcome.SENT else FlushOutcome.DROPPED
    }
}
