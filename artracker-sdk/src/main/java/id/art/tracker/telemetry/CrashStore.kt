package id.art.tracker.telemetry

import java.io.File
import java.io.FileOutputStream

/**
 * Crash items on disk, one `<id>.json` per crash, in `filesDir/artracker/crash`. Written synchronously by the
 * crash handler (no SQLite, no network) and turned into queue items on the next start by [CrashRecovery].
 * No method throws; every failure is reported through the return value.
 */
internal class CrashStore(private val dir: File) {
    /** Creates the directory ahead of time so the crash path does not have to. Best effort. */
    fun prepare() {
        runCatching { if (!dir.isDirectory) dir.mkdirs() }
    }

    /**
     * Writes [itemJson] to `<id>.tmp`, flushes it to disk (fsync, best effort) and renames it to `<id>.json`,
     * so a reader never sees half a file: either the whole item or nothing. Returns whether it succeeded.
     */
    fun writeSync(id: String, itemJson: String): Boolean {
        if (id.isEmpty() || id.indexOf('/') >= 0 || id.indexOf('\\') >= 0 || id.indexOf('\u0000') >= 0) return false
        var tmp: File? = null
        return try {
            if (!dir.isDirectory && !dir.mkdirs() && !dir.isDirectory) return false
            val t = File(dir, "$id$TMP_SUFFIX")
            tmp = t
            FileOutputStream(t).use { out ->
                out.write(itemJson.toByteArray(Charsets.UTF_8))
                out.flush()
                try {
                    out.fd.sync()
                } catch (_: Throwable) {
                    // best effort: some file systems do not support it
                }
            }
            val ok = t.renameTo(File(dir, "$id$FILE_SUFFIX"))
            if (!ok) t.delete()
            ok
        } catch (_: Throwable) {
            runCatching { tmp?.delete() }
            false
        }
    }

    /** Complete crash files, oldest first (file names are UUIDv7, so name order is time order). */
    fun pending(): List<File> = try {
        dir.listFiles { f -> f.isFile && f.name.endsWith(FILE_SUFFIX) }?.sortedBy { it.name } ?: emptyList()
    } catch (_: Throwable) {
        emptyList()
    }

    /** Text of [f], or null when unreadable or implausibly large (a crash item is at most a few tens of KB). */
    fun read(f: File): String? = try {
        if (f.length() > MAX_FILE_BYTES) null else f.readText(Charsets.UTF_8)
    } catch (_: Throwable) {
        null
    }

    fun delete(f: File) {
        runCatching { f.delete() }
    }

    /**
     * Removes `.tmp` leftovers of a write that died half way. Only files older than [minAgeMs] are touched:
     * another process of the same app may be writing one right now.
     */
    fun deleteStaleTemp(nowMs: Long, minAgeMs: Long = STALE_TMP_AGE_MS) {
        runCatching {
            dir.listFiles { f -> f.isFile && f.name.endsWith(TMP_SUFFIX) }?.forEach { f ->
                if (nowMs - f.lastModified() > minAgeMs) f.delete()
            }
        }
    }

    internal companion object {
        const val FILE_SUFFIX = ".json"
        const val TMP_SUFFIX = ".tmp"
        const val MAX_FILE_BYTES = 256L * 1024
        const val STALE_TMP_AGE_MS = 60_000L
    }
}
