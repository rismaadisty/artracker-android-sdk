package id.art.tracker.playground.local

import id.art.tracker.playground.boot.LocalServers
import java.io.File

/** Release stub: no MockWebServer in a release build, so nothing is available and the playground falls back to OFFLINE. */
object LocalServersProvider {
    val instance: LocalServers = object : LocalServers {
        override val sinkAvailable: Boolean = false
        override fun startSink(): Boolean = false
        override fun startTargets(): Boolean = false
        override val targetBaseUrl: String? = null
        override var screenshotAllowed: Boolean = false
        override fun screenshotSummary(): String? = null
        override fun exportScreenshots(dir: File): List<File>? = null
    }
}
