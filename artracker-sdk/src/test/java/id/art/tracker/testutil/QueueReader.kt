package id.art.tracker.testutil

import android.content.Context
import android.database.sqlite.SQLiteDatabase

/** Reads the persisted queue straight from SQLite (a second connection), for tests that inspect stored payloads. */
internal object QueueReader {
    /** Returns the payload text of every stored row and deletes those rows. */
    fun drain(context: Context, dbName: String = "artracker.db"): List<String> {
        val db = SQLiteDatabase.openDatabase(context.getDatabasePath(dbName).path, null, SQLiteDatabase.OPEN_READWRITE)
        try {
            val payloads = ArrayList<String>()
            val ids = ArrayList<String>()
            db.rawQuery("SELECT id, payload FROM items", null).use { c ->
                while (c.moveToNext()) {
                    ids += c.getString(0)
                    payloads += c.getString(1)
                }
            }
            ids.forEach { db.delete("items", "id = ?", arrayOf(it)) }
            return payloads
        } finally {
            db.close()
        }
    }
}
