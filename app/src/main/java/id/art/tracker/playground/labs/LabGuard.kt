package id.art.tracker.playground.labs

/** What an action does to the outside world; the guard decides whether it may run on the current endpoint. */
enum class LabActionKind { NORMAL, FAKE_PII, LOAD, CRASH }

object LabGuard {
    /**
     * Non-null (a reason to show on the card) when the action must not run. Load and fake-PII actions never run against
     * a REMOTE endpoint; crash actions keep their confirmation dialog instead of being blocked.
     */
    fun disabledReason(kind: LabActionKind, remote: Boolean): String? =
        if (remote && (kind == LabActionKind.FAKE_PII || kind == LabActionKind.LOAD)) {
            "dinonaktifkan pada backend nyata (ganti ke SINK atau OFFLINE di Pengaturan)"
        } else {
            null
        }
}
