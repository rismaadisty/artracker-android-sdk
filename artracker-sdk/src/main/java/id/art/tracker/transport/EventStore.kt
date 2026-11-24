package id.art.tracker.transport

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import id.art.tracker.core.Clock
import id.art.tracker.core.SdkLog
import id.art.tracker.core.UuidV7

/**
 * Persistent write-ahead queue (ARCHITECTURE.md section 9.2). Thread-safe: every compound operation runs
 * in one SQLite transaction. Hot-path methods never throw; on a database failure they log and degrade
 * (insert drops the items, claimBatch returns null).
 */
internal class EventStore(
    context: Context,
    private val clock: Clock,
    private val maxBytes: Long = 5L * 1024 * 1024,
    private val maxRows: Int = 5000,
    dbName: String = "artracker.db",
    private val heldWindowMs: Long = HELD_WINDOW_MS,
    private val maxHeldPerSession: Int = MAX_HELD_PER_SESSION,
) : ItemQueue {
    /** When this store (so this process) opened: [trimHeld] leaves older held rows to the launch decision. */
    private val openedAtMs: Long = clock.nowMs()

    private val helper = object : SQLiteOpenHelper(context.applicationContext, dbName, null, DB_VERSION) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE items (" +
                    "id TEXT PRIMARY KEY, " +
                    "priority INTEGER NOT NULL, " +
                    "created_at INTEGER NOT NULL, " +
                    "size_bytes INTEGER NOT NULL, " +
                    "payload TEXT NOT NULL, " +
                    "batch_id TEXT, " +
                    "attempts INTEGER NOT NULL DEFAULT 0, " +
                    "held INTEGER NOT NULL DEFAULT 0, " +
                    "session_id TEXT)",
            )
            db.execSQL("CREATE INDEX idx_items_pending ON items(batch_id, priority, created_at)")
            db.execSQL("CREATE INDEX idx_items_held_session ON items(held, session_id)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            if (oldVersion < 2) {
                // Rows queued before are ready to send: held = 0.
                db.execSQL("ALTER TABLE items ADD COLUMN held INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE items ADD COLUMN session_id TEXT")
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_items_held_session ON items(held, session_id)")
            }
        }
    }

    override fun insert(items: List<StoredItem>): Boolean {
        if (items.isEmpty()) return true
        return guarded("insert") { db ->
            db.inTransaction {
                val heldSessions = LinkedHashSet<String>()
                for (item in items) {
                    val values = ContentValues(8).apply {
                        put("id", item.id)
                        put("priority", item.priority)
                        put("created_at", item.createdAtMs)
                        put("size_bytes", item.sizeBytes)
                        put("payload", item.payloadJson)
                        put("attempts", 0)
                        put("held", if (item.held) 1 else 0)
                        put("session_id", item.sessionId)
                    }
                    db.insertWithOnConflict("items", null, values, SQLiteDatabase.CONFLICT_IGNORE)
                    if (item.held && item.sessionId != null) heldSessions += item.sessionId
                }
                heldSessions.forEach { trimHeld(db, it) }
                evictIfNeeded(db)
            }
            true
        } ?: false
    }

    override fun claimBatch(maxItems: Int, maxBytes: Int): Batch? =
        guarded("claimBatch") { db ->
            db.inTransaction {
                val claimed = ArrayList<StoredItem>()
                var total = 0L
                db.rawQuery(
                    "SELECT id, priority, created_at, payload, size_bytes FROM items " +
                        "WHERE batch_id IS NULL AND held = 0 ORDER BY priority, created_at, id LIMIT ?",
                    arrayOf(maxItems.coerceAtLeast(1).toString()),
                ).use { c ->
                    while (c.moveToNext()) {
                        val size = c.getInt(4)
                        if (claimed.isNotEmpty() && total + size > maxBytes) break
                        total += size
                        claimed += StoredItem(c.getString(0), c.getInt(1), c.getLong(2), c.getString(3))
                    }
                }
                if (claimed.isEmpty()) return@inTransaction null
                val batchId = UuidV7.generate(clock.nowMs())
                claimed.chunked(SQL_VARIABLE_CHUNK).forEach { chunk ->
                    val args = arrayOf<Any?>(batchId, *chunk.map { it.id }.toTypedArray())
                    db.execSQL("UPDATE items SET batch_id = ? WHERE id IN (${placeholders(chunk.size)})", args)
                }
                Batch(batchId, claimed)
            }
        }

    override fun complete(batchId: String) {
        guarded("complete") { db -> db.delete("items", "batch_id = ?", arrayOf(batchId)) }
    }

    override fun release(batchId: String) {
        guarded("release") { db ->
            db.execSQL(
                "UPDATE items SET batch_id = NULL, attempts = attempts + 1 WHERE batch_id = ?",
                arrayOf<Any?>(batchId),
            )
        }
    }

    override fun pendingCount(): Int =
        guarded("pendingCount") { db ->
            db.rawQuery("SELECT COUNT(*) FROM items WHERE batch_id IS NULL AND held = 0", null).use { c ->
                if (c.moveToFirst()) c.getInt(0) else 0
            }
        } ?: 0

    override fun resetOrphanedBatches() {
        guarded("resetOrphanedBatches") { db ->
            db.execSQL("UPDATE items SET batch_id = NULL WHERE batch_id IS NOT NULL")
        }
    }

    override fun clear() {
        guarded("clear") { db -> db.inTransaction { db.delete("items", null, null) } }
    }

    override fun purgeOlderThan(cutoffMs: Long) {
        guarded("purgeOlderThan") { db ->
            db.delete("items", "created_at < ? AND priority > 0", arrayOf(cutoffMs.toString()))
        }
    }

    override fun promoteHeld(sessionId: String): Int = promoteWhere("held = 1 AND session_id = ?", arrayOf(sessionId))

    override fun promoteHeldBefore(cutoffMs: Long): Int =
        promoteWhere("held = 1 AND created_at < ?", arrayOf(cutoffMs.toString()))

    override fun dropHeld(sessionId: String): Int = dropWhere("held = 1 AND session_id = ?", arrayOf(sessionId))

    override fun dropHeldBefore(cutoffMs: Long): Int = dropWhere("held = 1 AND created_at < ?", arrayOf(cutoffMs.toString()))

    private fun promoteWhere(where: String, args: Array<String>): Int =
        guarded("promoteHeld") { db ->
            db.update("items", ContentValues(1).apply { put("held", 0) }, where, args)
        } ?: 0

    private fun dropWhere(where: String, args: Array<String>): Int =
        guarded("dropHeld") { db -> db.delete("items", where, args) } ?: 0

    /**
     * Keeps the newest [heldWindowMs] of a session's held rows (by `created_at`) and at most [maxHeldPerSession].
     * Only rows created since this store opened are touched: held rows of an earlier process are left for the launch
     * decision (promote after a crash, drop otherwise).
     */
    private fun trimHeld(db: SQLiteDatabase, sessionId: String) {
        val mine = "held = 1 AND session_id = ? AND created_at >= ?"
        val args = arrayOf(sessionId, openedAtMs.toString())
        val newest = db.rawQuery("SELECT MAX(created_at) FROM items WHERE $mine", args).use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else return
        }
        db.delete("items", "$mine AND created_at < ?", arrayOf(sessionId, openedAtMs.toString(), (newest - heldWindowMs).toString()))
        val count = db.rawQuery("SELECT COUNT(*) FROM items WHERE $mine", args).use { c ->
            if (c.moveToFirst()) c.getInt(0) else 0
        }
        val excess = count - maxHeldPerSession
        if (excess > 0) {
            db.execSQL(
                "DELETE FROM items WHERE id IN (SELECT id FROM items WHERE $mine ORDER BY created_at ASC, id ASC LIMIT ?)",
                arrayOf<Any?>(sessionId, openedAtMs.toString(), excess),
            )
        }
    }

    fun close() {
        runCatching { helper.close() }
    }

    /**
     * Drops pending items, lowest value first, until both limits hold: held rows first (speculative performance
     * samples, never worth an item that is ready to send), then event/session, then error logs; oldest first within
     * each. Priority 0 and in-flight items are never evicted.
     */
    private fun evictIfNeeded(db: SQLiteDatabase) {
        var count = 0L
        var bytes = 0L
        db.rawQuery("SELECT COUNT(*), COALESCE(SUM(size_bytes), 0) FROM items", null).use { c ->
            if (c.moveToFirst()) {
                count = c.getLong(0)
                bytes = c.getLong(1)
            }
        }
        if (count <= maxRows && bytes <= maxBytes) return
        val victims = ArrayList<String>()
        db.rawQuery(
            "SELECT id, size_bytes FROM items WHERE priority > 0 AND batch_id IS NULL " +
                "ORDER BY held DESC, priority DESC, created_at ASC, id ASC",
            null,
        ).use { c ->
            while ((count > maxRows || bytes > maxBytes) && c.moveToNext()) {
                victims += c.getString(0)
                count--
                bytes -= c.getLong(1)
            }
        }
        victims.chunked(SQL_VARIABLE_CHUNK).forEach { chunk ->
            db.execSQL("DELETE FROM items WHERE id IN (${placeholders(chunk.size)})", chunk.toTypedArray())
        }
        if (victims.isNotEmpty()) SdkLog.d("evicted ${victims.size} queued items")
    }

    private inline fun <T> guarded(op: String, block: (SQLiteDatabase) -> T): T? =
        try {
            block(helper.writableDatabase)
        } catch (e: Exception) {
            SdkLog.w("EventStore.$op failed", e)
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

    private fun placeholders(n: Int): String = List(n) { "?" }.joinToString(",")

    internal companion object {
        private const val DB_VERSION = 2
        private const val SQL_VARIABLE_CHUNK = 500

        /** Held rows older than this (before the newest of their session) are trimmed: the minute before an error. */
        const val HELD_WINDOW_MS: Long = 60_000L

        /** At most this many held rows per session: 7 batches of 10 samples at the default interval. */
        const val MAX_HELD_PER_SESSION: Int = 7
    }
}
