package id.art.tracker.playground.status

/** Session ids seen by the host's status loop; read by the Session lab. Main-thread use only. */
object PlaygroundSessions {
    val history = SessionHistory()
}
