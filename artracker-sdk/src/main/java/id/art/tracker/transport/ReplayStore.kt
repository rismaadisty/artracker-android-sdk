package id.art.tracker.transport

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import id.art.tracker.core.Clock
import id.art.tracker.core.SdkLog
import id.art.tracker.core.UuidV7
import java.io.File
import java.io.IOException

/**
 * SQLite [ReplayQueue] (`artracker_replay.db`). Thread-safe: every compound operation runs in one transaction.
 *
 * The table keeps the metadata and the NAME of a file; the gzip body lives in a file under [bodiesDir]
 * (`noBackupFilesDir/artracker/replay/`). Android's `CursorWindow` is about 2 MB, so a large body stored as a BLOB
 * can be written but never read back; screenshot chunks reach 2 MB. A body file is written before its row and removed
 * with it (complete, eviction, clear, purge); [resetOrphaned] sweeps files no row points to.
 *
 * `seq` is allocated from `replay_seq`, which outlives the batches (complete/clear/eviction keep it), so a session that
 * continues after a process restart never reuses a `seq`: the server overwrites the stored object on a repeated `seq`
 * while ignoring the repeated ack.
 */
internal class ReplayStore(
    context: Context,
    private val clock: Clock,
    private val maxBytes: Long = DEFAULT_MAX_BYTES,
    private val maxBatches: Int = 200,
    private val maxAttempts: Int = 8,
    dbName: String = DB_NAME,
    private val bodiesDir: File = File(context.applicationContext.noBackupFilesDir, BODIES_PATH),
) : ReplayQueue {
    private val helper = object : SQLiteOpenHelper(context.applicationContext, dbName, null, DB_VERSION) {
        override fun onCreate(db: SQLiteDatabase) {
            createBatchesTable(db)
            db.execSQL(
                "CREATE TABLE replay_seq (" +
                    "session_id TEXT PRIMARY KEY, " +
                    "next_seq INTEGER NOT NULL, " +
                    "start_ms INTEGER NOT NULL, " +
                    "updated_at INTEGER NOT NULL)",
            )
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            // Every older layout only ever held wireframe (rrweb) batches, which the SDK can no longer produce or send.
            // The row (and, on the next [resetOrphaned], its body file) goes; the per-session `seq` counters stay.
            if (oldVersion < 2) {
                db.execSQL("DROP TABLE replay_batches")
                db.execSQL("DROP INDEX IF EXISTS idx_replay_pending")
                createBatchesTable(db)
            } else if (oldVersion < 3) {
                db.execSQL("DELETE FROM replay_batches WHERE format <> ?", arrayOf<Any?>(ReplayFormat.SCREEN_V1))
            }
        }
    }

    private fun createBatchesTable(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE replay_batches (" +
                "id TEXT PRIMARY KEY, " +
                "session_id TEXT NOT NULL, " +
                "seq INTEGER NOT NULL, " +
                "created_at INTEGER NOT NULL, " +
                "first_ts INTEGER NOT NULL, " +
                "last_ts INTEGER NOT NULL, " +
                "event_count INTEGER NOT NULL, " +
                "has_error INTEGER NOT NULL, " +
                "user_id TEXT, " +
                "environment TEXT NOT NULL, " +
                "file TEXT NOT NULL, " +
                "format TEXT NOT NULL DEFAULT '${ReplayFormat.SCREEN_V1}', " +
                "size_bytes INTEGER NOT NULL, " +
                "claimed INTEGER NOT NULL DEFAULT 0, " +
                "attempts INTEGER NOT NULL DEFAULT 0)",
        )
        db.execSQL("CREATE INDEX idx_replay_pending ON replay_batches(claimed, created_at, seq)")
    }

    override fun enqueue(batch: NewReplayBatch): Boolean =
        guarded("enqueue") { db ->
            val now = clock.nowMs()
            val id = UuidV7.generate(now)
            val name = fileNameFor(id)
            writeBody(name, batch.gzipBody)
            try {
                db.inTransaction {
                val seq = nextSeq(db, batch, now)
                val values = ContentValues(15).apply {
                    put("id", id)
                    put("session_id", batch.sessionId)
                    put("seq", seq)
                    put("created_at", now)
                    put("first_ts", batch.firstTsMs)
                    put("last_ts", batch.lastTsMs)
                    put("event_count", batch.eventCount)
                    put("has_error", if (batch.hasError) 1 else 0)
                    put("user_id", batch.userId)
                    put("environment", batch.environment)
                    put("file", name)
                    put("format", batch.format)
                    put("size_bytes", batch.gzipBody.size)
                    put("claimed", 0)
                    put("attempts", 0)
                }
                db.insertOrThrow("replay_batches", null, values)
                evictIfNeeded(db)
                }
            } catch (e: Exception) {
                deleteBody(name) // no row will ever point to it
                throw e
            }
            true
        } ?: false

    override fun claim(): ReplayUpload? =
        guarded("claim") { db ->
            db.inTransaction {
                deleteRows(db, "claimed = 0 AND attempts >= ?", arrayOf(maxAttempts.toString()))
                var claimed: ReplayUpload? = null
                while (claimed == null) {
                    val row = nextPending(db) ?: break
                    val body = readBody(row.file)
                    if (body == null) {
                        // The file is gone or unreadable: the batch can never be uploaded.
                        SdkLog.w("replay batch body missing; dropped")
                        deleteRows(db, "id = ?", arrayOf(row.upload.id))
                        continue
                    }
                    db.execSQL("UPDATE replay_batches SET claimed = 1 WHERE id = ?", arrayOf<Any?>(row.upload.id))
                    claimed = row.upload.withBody(body)
                }
                claimed
            }
        }

    private class Pending(val upload: ReplayUpload, val file: String)

    private fun ReplayUpload.withBody(body: ByteArray) = ReplayUpload(
        id, sessionId, seq, body, eventCount, startMs, durationMs, hasError, userId, environment, format,
    )

    private fun nextPending(db: SQLiteDatabase): Pending? =
        db.rawQuery(
            "SELECT b.id, b.session_id, b.seq, b.file, b.event_count, b.first_ts, b.last_ts, b.has_error, " +
                "b.user_id, b.environment, b.format, s.start_ms " +
                "FROM replay_batches b LEFT JOIN replay_seq s ON s.session_id = b.session_id " +
                "WHERE b.claimed = 0 ORDER BY b.created_at, b.seq, b.rowid LIMIT 1",
            null,
        ).use { c ->
            if (!c.moveToFirst()) return@use null
            val firstTs = c.getLong(5)
            val start = if (c.isNull(11)) firstTs else c.getLong(11)
            Pending(
                ReplayUpload(
                    id = c.getString(0),
                    sessionId = c.getString(1),
                    seq = c.getInt(2),
                    gzipBody = EMPTY,
                    eventCount = c.getInt(4),
                    startMs = start,
                    durationMs = (c.getLong(6) - start).coerceAtLeast(0L),
                    hasError = c.getInt(7) != 0,
                    userId = if (c.isNull(8)) null else c.getString(8),
                    environment = c.getString(9),
                    format = c.getString(10),
                ),
                c.getString(3),
            )
        }

    override fun complete(id: String) {
        guarded("complete") { db -> deleteRows(db, "id = ?", arrayOf(id)) }
    }

    override fun release(id: String) {
        guarded("release") { db ->
            db.execSQL(
                "UPDATE replay_batches SET claimed = 0, attempts = attempts + 1 WHERE id = ?",
                arrayOf<Any?>(id),
            )
        }
    }

    override fun pendingCount(): Int =
        guarded("pendingCount") { db ->
            db.rawQuery(
                "SELECT COUNT(*) FROM replay_batches WHERE claimed = 0 AND attempts < ?",
                arrayOf(maxAttempts.toString()),
            ).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
        } ?: 0

    override fun resetOrphaned() {
        guarded("resetOrphaned") { db ->
            db.execSQL("UPDATE replay_batches SET claimed = 0 WHERE claimed = 1")
            sweepOrphanFiles(db)
        }
    }

    override fun clear() {
        guarded("clear") { db -> db.inTransaction { deleteRows(db, null, null) } }
    }

    override fun clearFormat(format: String) {
        guarded("clearFormat") { db -> db.inTransaction { deleteRows(db, "claimed = 0 AND format = ?", arrayOf(format)) } }
    }

    override fun purgeOlderThan(cutoffMs: Long) {
        guarded("purgeOlderThan") { db ->
            db.inTransaction {
                val arg = arrayOf(cutoffMs.toString())
                deleteRows(db, "created_at < ?", arg)
                db.delete("replay_seq", "updated_at < ?", arg)
            }
        }
    }

    fun close() {
        runCatching { helper.close() }
    }

    private fun nextSeq(db: SQLiteDatabase, batch: NewReplayBatch, now: Long): Int {
        val current = db.rawQuery(
            "SELECT next_seq FROM replay_seq WHERE session_id = ?",
            arrayOf(batch.sessionId),
        ).use { c -> if (c.moveToFirst()) c.getInt(0) else null }
        if (current == null) {
            val values = ContentValues(4).apply {
                put("session_id", batch.sessionId)
                put("next_seq", 1)
                put("start_ms", batch.firstTsMs)
                put("updated_at", now)
            }
            db.insertOrThrow("replay_seq", null, values)
            return 0
        }
        db.execSQL(
            "UPDATE replay_seq SET next_seq = ?, updated_at = ? WHERE session_id = ?",
            arrayOf<Any?>(current + 1, now, batch.sessionId),
        )
        return current
    }

    /** Drops the oldest pending batches until both caps hold. In-flight batches count but are never evicted. */
    private fun evictIfNeeded(db: SQLiteDatabase) {
        var count = 0L
        var bytes = 0L
        db.rawQuery("SELECT COUNT(*), COALESCE(SUM(size_bytes), 0) FROM replay_batches", null).use { c ->
            if (c.moveToFirst()) {
                count = c.getLong(0)
                bytes = c.getLong(1)
            }
        }
        if (count <= maxBatches && bytes <= maxBytes) return
        val victims = ArrayList<String>()
        val files = ArrayList<String>()
        db.rawQuery(
            "SELECT id, size_bytes, file FROM replay_batches WHERE claimed = 0 ORDER BY created_at ASC, seq ASC, rowid ASC",
            null,
        ).use { c ->
            while ((count > maxBatches || bytes > maxBytes) && c.moveToNext()) {
                victims += c.getString(0)
                files += c.getString(2)
                count--
                bytes -= c.getLong(1)
            }
        }
        victims.chunked(SQL_VARIABLE_CHUNK).forEach { chunk ->
            db.execSQL(
                "DELETE FROM replay_batches WHERE id IN (${List(chunk.size) { "?" }.joinToString(",")})",
                chunk.toTypedArray(),
            )
        }
        files.forEach(::deleteBody)
        if (victims.isNotEmpty()) SdkLog.d("evicted ${victims.size} queued replay batches")
    }

    /** Deletes the rows matching [where] and their body files. Call inside a transaction. */
    private fun deleteRows(db: SQLiteDatabase, where: String?, args: Array<String>?) {
        val files = ArrayList<String>()
        db.rawQuery("SELECT file FROM replay_batches" + (where?.let { " WHERE $it" } ?: ""), args).use { c ->
            while (c.moveToNext()) files += c.getString(0)
        }
        db.delete("replay_batches", where, args)
        files.forEach(::deleteBody)
    }

    private fun fileNameFor(id: String): String = "$id.gz"

    private fun writeBody(name: String, body: ByteArray) {
        if (!bodiesDir.isDirectory && !bodiesDir.mkdirs() && !bodiesDir.isDirectory) throw IOException("cannot create body directory")
        val tmp = File(bodiesDir, "$name.tmp")
        tmp.writeBytes(body)
        if (!tmp.renameTo(File(bodiesDir, name))) {
            tmp.delete()
            throw IOException("cannot store body")
        }
    }

    private fun readBody(name: String): ByteArray? = try {
        File(bodiesDir, name).takeIf { it.isFile }?.readBytes()
    } catch (e: IOException) {
        null
    }

    private fun deleteBody(name: String) {
        runCatching { File(bodiesDir, name).delete() }
    }

    /** Files that no row names (a crash between the file write and the row insert) are deleted. */
    private fun sweepOrphanFiles(db: SQLiteDatabase) {
        val known = HashSet<String>()
        db.rawQuery("SELECT file FROM replay_batches", null).use { c -> while (c.moveToNext()) known += c.getString(0) }
        bodiesDir.listFiles()?.forEach { f -> if (f.name !in known) runCatching { f.delete() } }
    }

    private inline fun <T> guarded(op: String, block: (SQLiteDatabase) -> T): T? =
        try {
            block(helper.writableDatabase)
        } catch (e: Exception) {
            SdkLog.w("ReplayStore.$op failed", e)
            null
        }

    private inline fun <T> SQLiteDatabase.inTransaction(block: () -> T): T {
        beginTransaction()
        try {
            val result = block()
            setTransactionSuccessful()
            return result
        } finally {
            endTransaction()
        }
    }

    companion object {
        const val DB_NAME = "artracker_replay.db"
        private const val DB_VERSION = 3
        const val DEFAULT_MAX_BYTES: Long = 50L * 1024 * 1024
        private const val BODIES_PATH = "artracker/replay"
        private val EMPTY = ByteArray(0)
        private const val SQL_VARIABLE_CHUNK = 500
    }
}
