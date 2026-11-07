package id.art.tracker.playground.local

import id.art.tracker.playground.boot.LocalServers
import java.io.File

/** Debug implementation. Temporary: nothing is started yet; the sink arrives in Task 3, the target server in Task 10. */
object LocalServersProvider {
    val instance: LocalServers = object : LocalServers {
        override val sinkAvailable: Boolean = true
        override fun startSink(): Boolean = false
        override fun startTargets(): Boolean = false
        override val targetBaseUrl: String? = null
        override fun exportReplayChunks(dir: File): File? = null
    }
}
