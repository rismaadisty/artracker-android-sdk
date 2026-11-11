package id.art.tracker.perf

import java.io.File

/** Reads the raw `/proc/self` files; null when unreadable (some OEM SELinux policies block them). */
internal interface ProcessReader {
    fun readStat(): String?

    fun readStatm(): String?
}

internal object ProcSelfReader : ProcessReader {
    override fun readStat(): String? = read("/proc/self/stat")

    override fun readStatm(): String? = read("/proc/self/statm")

    private fun read(path: String): String? = try {
        File(path).readText()
    } catch (t: Throwable) {
        null
    }
}
