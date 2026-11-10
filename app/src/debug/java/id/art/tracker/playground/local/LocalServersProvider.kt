package id.art.tracker.playground.local

import id.art.tracker.playground.boot.LocalServers
import java.io.File

/** Debug implementation: the loopback sink (Task 3) and the Network lab's target server (Task 10). */
object LocalServersProvider {
    private val sink = InAppSink()
    private val targets = HttpTargetServer()

    val instance: LocalServers = object : LocalServers {
        override val sinkAvailable: Boolean = true
        override fun startSink(): Boolean = sink.start()
        override fun startTargets(): Boolean = targets.start()
        override val targetBaseUrl: String? get() = if (targets.boundAddress != null) targets.baseUrl else null
        override fun exportReplayChunks(dir: File): File? = sink.exportReplayChunks(dir)
        override var screenshotAllowed: Boolean
            get() = sink.screenshotEnabled
            set(value) { sink.screenshotEnabled = value }
        override fun screenshotSummary(): String? = sink.screenshotStats().let {
            "${it.chunks} chunk, ${it.frames} frame, ${it.touches} sentuhan, ${it.bytes / 1024} KB"
        }
        override fun exportScreenshots(dir: File): List<File>? = sink.exportScreenshots(dir)
    }
}
