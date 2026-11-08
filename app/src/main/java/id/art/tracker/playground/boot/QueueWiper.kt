package id.art.tracker.playground.boot

import java.io.File

/**
 * Deletes the SDK's persisted queue before `init`. The SDK flushes leftover items right after init, so anything queued
 * against a local sink (fake PII, bursts, a fake crash) must not be there when a REMOTE endpoint becomes active.
 *
 * File names are the ones documented in the README ("Backups"): `artracker.db` (+ SQLite side files) and
 * `filesDir/artracker/` (crash files). `SafetyTest` (instrumented) checks they still match the real SDK.
 */
object QueueWiper {
    private val DB_FILES = listOf("artracker.db", "artracker.db-wal", "artracker.db-shm", "artracker.db-journal")

    /** Returns the number of entries deleted (each SQLite file counts 1, the crash directory counts 1). */
    fun wipe(databasesDir: File, filesDir: File): Int {
        var deleted = 0
        for (name in DB_FILES) {
            val f = File(databasesDir, name)
            if (f.exists() && f.delete()) deleted++
        }
        val crash = File(filesDir, "artracker")
        if (crash.exists() && crash.deleteRecursively()) deleted++
        return deleted
    }
}
