package id.art.tracker.playground.status

import id.art.tracker.ARTracker
import id.art.tracker.Diagnostics

data class SdkStatus(
    val initialized: Boolean,
    val sessionId: String?,
    val pending: Int,
    val lastSend: String?,
    val disabled: Boolean,
    val optedOutIntent: Boolean,
    val replayIntent: Boolean,
) {
    /** The SDK is disabled but the user did not opt out: the server rejected the key (kill-switch). */
    val disabledByOtherThanOptOut: Boolean get() = disabled && !optedOutIntent
}

/** `poll()` reads the database through `ARTracker.diagnostics()`: call it on `Dispatchers.IO`, never on the main thread. */
class SdkStatusRepository(
    private val source: () -> Diagnostics = ARTracker::diagnostics,
    private val controls: SdkControls,
) {
    fun poll(): SdkStatus {
        val d = source()
        return SdkStatus(
            initialized = d.initialized,
            sessionId = d.sessionId,
            pending = d.pendingItems,
            lastSend = d.lastSendResult,
            disabled = d.disabled,
            optedOutIntent = controls.optedOut,
            replayIntent = controls.replayOn,
        )
    }
}
