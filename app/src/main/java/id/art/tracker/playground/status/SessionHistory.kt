package id.art.tracker.playground.status

/** The distinct session ids observed over time, oldest first, bounded. Main-thread use only. */
class SessionHistory(private val max: Int = 10) {
    private val list = ArrayList<String>()
    val ids: List<String> get() = list.toList()

    fun observe(sessionId: String?) {
        if (sessionId == null || list.lastOrNull() == sessionId) return
        list += sessionId
        while (list.size > max) list.removeAt(0)
    }
}
