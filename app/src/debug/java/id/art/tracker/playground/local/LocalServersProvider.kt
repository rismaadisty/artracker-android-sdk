package id.art.tracker.playground.local

import id.art.tracker.playground.boot.LocalServers
import java.io.File

/** Debug implementation: the sink is real (Task 3); the target server arrives in Task 10. */
object LocalServersProvider {
    private val sink = InAppSink()

    val instance: LocalServers = object : LocalServers {
        override val sinkAvailable: Boolean = true
        override fun startSink(): Boolean = sink.start()
        override fun startTargets(): Boolean = false
        override val targetBaseUrl: String? = null
        override fun exportReplayChunks(dir: File): File? = sink.exportReplayChunks(dir)
    }
}
