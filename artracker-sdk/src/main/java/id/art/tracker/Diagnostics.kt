package id.art.tracker

/**
 * Snapshot of the SDK state, for debug screens and support tooling. See [ARTracker.diagnostics].
 *
 * @property initialized true once [ARTracker.init] succeeded.
 * @property sessionId id of the current session, null before init.
 * @property pendingItems number of items persisted in the local queue and waiting to be sent. Items still in the
 * in-memory hand-off buffer (not yet written to disk) are not counted.
 * @property lastSendResult short description of the last meaningful send outcome (`accepted`, `network_error`,
 * `unauthorized`, `retry_after`, `disabled`, ...), null until a batch was attempted.
 * @property disabled true while the SDK records and sends nothing: the user opted out, or the kill-switch tripped
 * after the server rejected the API key.
 */
public data class Diagnostics(
    val initialized: Boolean,
    val sessionId: String?,
    val pendingItems: Int,
    val lastSendResult: String?,
    val disabled: Boolean,
)
