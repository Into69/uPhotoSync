package dev.unifiphotosync.app.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/**
 * Local record of which MediaStore items are already on the NAS, so the app doesn't
 * have to list the share to know what's backed up. Keyed by media id; the size is
 * kept so an edited photo (new size) gets uploaded again.
 */
class BackupIndex(context: Context) :
    SQLiteOpenHelper(context, "backup_index.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE backed_up(" +
                "media_id INTEGER PRIMARY KEY, " +
                "remote_path TEXT NOT NULL, " +
                "size INTEGER NOT NULL, " +
                "backed_up_at INTEGER NOT NULL)"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    private val _sizes = MutableStateFlow<Map<Long, Long>>(emptyMap())
    private var loaded = false

    /** media id -> size of the copy on the NAS. Updates live as uploads finish. */
    val sizes: StateFlow<Map<Long, Long>>
        get() {
            ensureLoaded()
            return _sizes
        }

    @Synchronized
    private fun ensureLoaded() {
        if (loaded) return
        val map = HashMap<Long, Long>()
        readableDatabase.rawQuery("SELECT media_id, size FROM backed_up", null).use { c ->
            while (c.moveToNext()) map[c.getLong(0)] = c.getLong(1)
        }
        _sizes.value = map
        loaded = true
    }

    fun isBackedUp(item: MediaItem): Boolean = sizes.value[item.id] == item.size

    fun mark(mediaId: Long, remotePath: String, size: Long) {
        ensureLoaded()
        writableDatabase.insertWithOnConflict(
            "backed_up", null,
            ContentValues().apply {
                put("media_id", mediaId)
                put("remote_path", remotePath)
                put("size", size)
                put("backed_up_at", System.currentTimeMillis())
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
        _sizes.update { it + (mediaId to size) }
    }

    /** media id -> (path on the NAS, size) for every recorded upload. */
    fun entries(): Map<Long, Pair<String, Long>> {
        val map = HashMap<Long, Pair<String, Long>>()
        readableDatabase.rawQuery("SELECT media_id, remote_path, size FROM backed_up", null).use { c ->
            while (c.moveToNext()) map[c.getLong(0)] = c.getString(1) to c.getLong(2)
        }
        return map
    }

    /** Forgets uploads whose copy is gone from the NAS, so they get uploaded again. */
    fun remove(ids: Collection<Long>) {
        if (ids.isEmpty()) return
        ensureLoaded()
        val db = writableDatabase
        db.beginTransaction()
        try {
            ids.forEach { db.delete("backed_up", "media_id = ?", arrayOf(it.toString())) }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        _sizes.update { it - ids.toSet() }
    }

    /**
     * Forgets uploads stored at [path] on the NAS, or anywhere under it if it's a folder,
     * after they were deleted there. Their photos show as not backed up straight away.
     */
    fun forgetRemote(path: String) {
        ensureLoaded()
        val clean = NasPath.clean(path)
        // SMB paths are case-insensitive. ESCAPE keeps _ and % in names literal.
        val escaped = clean.lowercase().replace("!", "!!").replace("%", "!%").replace("_", "!_")
        val removed = ArrayList<Long>()
        readableDatabase.rawQuery(
            "SELECT media_id FROM backed_up WHERE lower(remote_path) = ? OR lower(remote_path) LIKE ? ESCAPE '!'",
            arrayOf(clean.lowercase(), "$escaped\\%"),
        ).use { c -> while (c.moveToNext()) removed += c.getLong(0) }
        remove(removed)
    }

    @Synchronized
    fun clear() {
        writableDatabase.delete("backed_up", null, null)
        _sizes.value = emptyMap()
        loaded = true
    }
}
