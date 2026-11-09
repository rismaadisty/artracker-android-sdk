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
) : ItemQueue {
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
                    "attempts INTEGER NOT NULL DEFAULT 0)",
            )
            db.execSQL("CREATE INDEX idx_items_pending ON items(batch_id, priority, created_at)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    }

    override fun insert(items: List<StoredItem>): Boolean {
        if (items.isEmpty()) return true
        return guarded("insert") { db ->
            db.inTransaction {
                for (item in items) {
                    val values = ContentValues(6).apply {
                        put("id", item.id)
                        put("priority", item.priority)
                        put("created_at", item.createdAtMs)
                        put("size_bytes", item.sizeBytes)
                        put("payload", item.payloadJson)
                        put("attempts", 0)
                    }
                    db.insertWithOnConflict("items", null, values, SQLiteDatabase.CONFLICT_IGNORE)
                }
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
                        "WHERE batch_id IS NULL ORDER BY priority, created_at, id LIMIT ?",
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
            db.rawQuery("SELECT COUNT(*) FROM items WHERE batch_id IS NULL", null).use { c ->
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

    fun close() {
        runCatching { helper.close() }
    }

    /**
     * Drops pending items, lowest value first (event/session, then error logs; oldest
     * first within a priority) until both limits hold. Priority 0 and in-flight items are never evicted.
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
                "ORDER BY priority DESC, created_at ASC, id ASC",
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

    private companion object {
        const val DB_VERSION = 1
        const val SQL_VARIABLE_CHUNK = 500
    }
}
