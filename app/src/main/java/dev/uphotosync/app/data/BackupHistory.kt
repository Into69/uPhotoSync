package dev.uphotosync.app.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** One finished run, for Settings > Backup history. */
data class HistoryEntry(
    val id: Long,
    /** "Scheduled backup", "Move to NAS", "Full check", ... */
    val kind: String,
    val startedAt: Long,
    val finishedAt: Long,
    val outcome: Outcome,
    /** One-line result, e.g. "12 uploaded, 340 already on the NAS". */
    val summary: String,
    val uploaded: Int = 0,
    val alreadyThere: Int = 0,
    val failed: Int = 0,
    val bytes: Long = 0,
    /** "file: reason" per problem. */
    val errors: List<String> = emptyList(),
) {
    enum class Outcome { Success, Partial, Failed, Skipped }
}

/** A log of recent backups, moves, checks and downloads, newest first; keeps the last [KEEP]. */
class BackupHistory(context: Context) : SQLiteOpenHelper(context, "history.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE runs(" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, kind TEXT NOT NULL, started_at INTEGER NOT NULL, " +
                "finished_at INTEGER NOT NULL, outcome TEXT NOT NULL, summary TEXT NOT NULL, " +
                "uploaded INTEGER NOT NULL, already INTEGER NOT NULL, failed INTEGER NOT NULL, " +
                "bytes INTEGER NOT NULL, errors TEXT NOT NULL)"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    private val _entries = MutableStateFlow<List<HistoryEntry>>(emptyList())
    private var loaded = false

    /** Newest first. Updates live as runs finish. */
    val entries: StateFlow<List<HistoryEntry>>
        get() {
            ensureLoaded()
            return _entries.asStateFlow()
        }

    @Synchronized
    private fun ensureLoaded() {
        if (loaded) return
        _entries.value = query()
        loaded = true
    }

    private fun query(): List<HistoryEntry> {
        val out = ArrayList<HistoryEntry>()
        readableDatabase.rawQuery("SELECT * FROM runs ORDER BY finished_at DESC LIMIT $KEEP", null).use { c ->
            while (c.moveToNext()) {
                out += HistoryEntry(
                    id = c.getLong(c.getColumnIndexOrThrow("id")),
                    kind = c.getString(c.getColumnIndexOrThrow("kind")),
                    startedAt = c.getLong(c.getColumnIndexOrThrow("started_at")),
                    finishedAt = c.getLong(c.getColumnIndexOrThrow("finished_at")),
                    outcome = runCatching { HistoryEntry.Outcome.valueOf(c.getString(c.getColumnIndexOrThrow("outcome"))) }
                        .getOrDefault(HistoryEntry.Outcome.Success),
                    summary = c.getString(c.getColumnIndexOrThrow("summary")),
                    uploaded = c.getInt(c.getColumnIndexOrThrow("uploaded")),
                    alreadyThere = c.getInt(c.getColumnIndexOrThrow("already")),
                    failed = c.getInt(c.getColumnIndexOrThrow("failed")),
                    bytes = c.getLong(c.getColumnIndexOrThrow("bytes")),
                    errors = c.getString(c.getColumnIndexOrThrow("errors")).split('\n').filter { it.isNotBlank() },
                )
            }
        }
        return out
    }

    @Synchronized
    fun add(entry: HistoryEntry) {
        val db = writableDatabase
        db.insert("runs", null, ContentValues().apply {
            put("kind", entry.kind)
            put("started_at", entry.startedAt)
            put("finished_at", entry.finishedAt)
            put("outcome", entry.outcome.name)
            put("summary", entry.summary)
            put("uploaded", entry.uploaded)
            put("already", entry.alreadyThere)
            put("failed", entry.failed)
            put("bytes", entry.bytes)
            put("errors", entry.errors.joinToString("\n") { it.replace('\n', ' ') })
        })
        db.execSQL("DELETE FROM runs WHERE id NOT IN (SELECT id FROM runs ORDER BY finished_at DESC LIMIT $KEEP)")
        _entries.value = query()
        loaded = true
    }

    @Synchronized
    fun clear() {
        writableDatabase.delete("runs", null, null)
        _entries.value = emptyList()
        loaded = true
    }

    private companion object {
        const val KEEP = 200
    }
}
