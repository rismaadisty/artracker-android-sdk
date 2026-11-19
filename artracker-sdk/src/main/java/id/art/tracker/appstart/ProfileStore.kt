package id.art.tracker.appstart

import id.art.tracker.core.Clock
import id.art.tracker.core.SdkLog
import java.io.File
import java.io.IOException

/**
 * Pending startup profiles, one gzip file each in [dir] (`filesDir/artracker/appstart`), named `<startId>.a<attempts>.gz`.
 * At most [MAX_FILES] are kept (the oldest is evicted by a write), a file older than [MAX_AGE_MS] is deleted, and one that
 * failed [MAX_ATTEMPTS] times is deleted. A write is atomic (`.tmp`, then a rename). Blocking file I/O: never call it on the
 * main thread. Ages are read from the file's modification time, which [write] sets from [clock].
 */
internal class ProfileStore(private val dir: File, private val clock: Clock) {
    data class Pending(val file: File, val startId: String, val attempts: Int)

    /** Atomic tmp + rename to `<startId>.a0.gz`; evicts the oldest beyond [MAX_FILES]. False when it could not be stored. */
    @Synchronized
    fun write(startId: String, gzip: ByteArray): Boolean {
        if (!validId(startId)) {
            SdkLog.w("app start profile not stored: invalid start id")
            return false
        }
        return try {
            if (!dir.isDirectory && !dir.mkdirs() && !dir.isDirectory) return false
            val tmp = File(dir, "$startId$TMP")
            val target = File(dir, "$startId.a0$SUFFIX")
            tmp.writeBytes(gzip)
            tmp.setLastModified(clock.nowMs())
            if (!tmp.renameTo(target)) {
                tmp.delete()
                return false
            }
            evictOldest()
            true
        } catch (e: IOException) {
            SdkLog.w("app start profile not stored", e)
            false
        } catch (e: SecurityException) {
            SdkLog.w("app start profile not stored", e)
            false
        }
    }

    /** Oldest first. Deletes files older than [MAX_AGE_MS] and stale `.tmp` files first. */
    @Synchronized
    fun pending(): List<Pending> {
        val now = clock.nowMs()
        val files = dir.listFiles() ?: return emptyList()
        val found = ArrayList<Pair<Pending, Long>>()
        for (file in files) {
            val name = file.name
            if (name.endsWith(TMP)) {
                if (now - file.lastModified() > STALE_TMP_MS) file.delete()
                continue
            }
            val match = NAME.matchEntire(name) ?: continue
            val modified = file.lastModified()
            if (now - modified > MAX_AGE_MS) {
                file.delete()
                continue
            }
            val attempts = match.groupValues[2].toIntOrNull() ?: continue
            found += Pending(file, match.groupValues[1], attempts) to modified
        }
        return found.sortedWith(compareBy({ it.second }, { it.first.startId })).map { it.first }
    }

    @Synchronized
    fun delete(p: Pending) {
        p.file.delete()
    }

    /** Renames to `attempts + 1` (keeping the file's age); deletes it when that reaches [MAX_ATTEMPTS]. */
    @Synchronized
    fun markFailed(p: Pending) {
        val attempts = p.attempts + 1
        if (attempts >= MAX_ATTEMPTS) {
            p.file.delete()
            return
        }
        val modified = p.file.lastModified()
        val renamed = File(dir, "${p.startId}.a$attempts$SUFFIX")
        if (p.file.renameTo(renamed)) renamed.setLastModified(modified)
    }

    /** True when the directory holds any profile file (cheap: no deletion, no sorting). */
    fun hasFiles(): Boolean = dir.listFiles()?.any { NAME.matches(it.name) } == true

    private fun evictOldest() {
        val all = pending()
        if (all.size <= MAX_FILES) return
        all.take(all.size - MAX_FILES).forEach { it.file.delete() }
    }

    /** The id becomes part of a file name: no separator, no `..`, not empty. */
    private fun validId(id: String): Boolean =
        id.isNotEmpty() && id != "." && id != ".." && id.none { it == '/' || it == '\\' || it == '\u0000' } && id.length <= MAX_ID_LENGTH

    companion object {
        const val MAX_FILES = 3
        const val MAX_AGE_MS = 3 * 24 * 3_600_000L
        const val MAX_ATTEMPTS = 5
        const val DIR = "artracker/appstart"

        private const val SUFFIX = ".gz"
        private const val TMP = ".tmp"
        private const val STALE_TMP_MS = 600_000L
        private const val MAX_ID_LENGTH = 100
        private val NAME = Regex("^(.+)\\.a(\\d+)\\.gz$")
    }
}
