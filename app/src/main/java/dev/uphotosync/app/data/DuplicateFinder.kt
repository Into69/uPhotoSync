package dev.uphotosync.app.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.graphics.Bitmap
import android.provider.MediaStore
import android.util.Size
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.security.MessageDigest

/**
 * Finds duplicate photos and videos on the phone.
 *
 * - Exact copies: same size, then the same SHA-256 of the whole file. Certain.
 * - Similar photos: a 64-bit "difference hash" of a tiny greyscale thumbnail. Burst shots,
 *   re-saves and resized copies land a few bits apart. A judgment call, so it's shown as
 *   "similar", with how close, and the user decides.
 *
 * Fingerprints are cached per file (by id, size and modified time), so a second scan only
 * reads new or changed files.
 */
class DuplicateFinder(private val context: Context) {

    data class Match(
        val item: MediaItem,
        /** Byte-for-byte the same file as the one kept. */
        val exact: Boolean,
        /** For similar photos: how alike, 0-100. */
        val similarity: Int,
    )

    /**
     * Files that are copies or near-copies of each other. [suggestedKeep] is the best one to
     * keep; the user can pick another, so how each file relates is worked out against
     * whichever is kept ([relation]).
     */
    data class Group(
        val items: List<MediaItem>,
        val suggestedKeep: Long,
        private val sha: Map<Long, String>,
        private val dhash: Map<Long, Long>,
    ) {
        fun relation(item: MediaItem, keep: MediaItem): Match {
            val a = sha[item.id]
            val exact = item.size == keep.size && a != null && a == sha[keep.id]
            val x = dhash[item.id]
            val y = dhash[keep.id]
            val similarity = when {
                exact -> 100
                x != null && y != null -> 100 - java.lang.Long.bitCount(x xor y) * 100 / 64
                else -> 0
            }
            return Match(item, exact, similarity)
        }

        /** Everything but [keep], exact copies first, then the most alike. */
        fun others(keep: MediaItem): List<Match> = items.filter { it.id != keep.id }.map { relation(it, keep) }
            .sortedWith(compareByDescending<Match> { it.exact }.thenByDescending { it.similarity })

        fun without(ids: Set<Long>) = copy(items = items.filterNot { it.id in ids })
    }

    data class Progress(val step: String, val done: Int, val total: Int)

    private val cache = FingerprintCache(context)

    /**
     * Groups of duplicates in [items]. [similar] adds near-duplicate photos within
     * [maxDistance] bits of each other (of 64); exact copies are always included.
     */
    suspend fun scan(
        items: List<MediaItem>,
        similar: Boolean,
        maxDistance: Int,
        onProgress: (Progress) -> Unit,
    ): List<Group> = withContext(Dispatchers.Default) {
        val known = cache.load()
        fun fp(item: MediaItem) = known[item.id]?.takeIf { it.size == item.size && it.modified == item.modifiedAt }

        // 1. Exact copies: only files sharing a size can be identical, so only those are read.
        val sameSize = items.filter { it.size > 0 }.groupBy { it.size }.values.filter { it.size > 1 }.flatten()
        val sha = HashMap<Long, String>()
        sameSize.forEachIndexed { i, item ->
            currentCoroutineContext().ensureActive()
            onProgress(Progress("Comparing files", i, sameSize.size))
            val hash = fp(item)?.sha ?: sha256(item)?.also { cache.putSha(item, it) }
            if (hash != null) sha[item.id] = hash
        }

        // Union-find over item indexes: exact copies first, then similar photos.
        val index = items.withIndex().associate { (i, it) -> it.id to i }
        val parent = IntArray(items.size) { it }
        fun find(x: Int): Int {
            var r = x
            while (parent[r] != r) r = parent[r]
            var c = x
            while (parent[c] != r) { val n = parent[c]; parent[c] = r; c = n }
            return r
        }
        fun union(a: Int, b: Int) {
            val ra = find(a)
            val rb = find(b)
            if (ra != rb) parent[rb] = ra
        }
        sameSize.filter { it.id in sha }.groupBy { it.size to sha[it.id] }.values.forEach { copies ->
            copies.zipWithNext { a, b -> union(index.getValue(a.id), index.getValue(b.id)) }
        }

        // 2. Similar photos (not videos: one frame says too little about a whole video).
        val dhash = HashMap<Long, Long>()
        if (similar) {
            val photos = items.filter { !it.isVideo }
            photos.forEachIndexed { i, item ->
                currentCoroutineContext().ensureActive()
                if (i % 10 == 0) onProgress(Progress("Looking at photos", i, photos.size))
                val h = fp(item)?.dhash ?: differenceHash(item)?.also { cache.putDhash(item, it) }
                if (h != null) dhash[item.id] = h
            }
            val withHash = photos.filter { it.id in dhash }
            val hashes = LongArray(withHash.size) { dhash.getValue(withHash[it].id) }
            for (i in hashes.indices) {
                if (i % 200 == 0) {
                    currentCoroutineContext().ensureActive()
                    onProgress(Progress("Grouping similar photos", i, hashes.size))
                }
                val hi = hashes[i]
                for (j in i + 1 until hashes.size) {
                    if (java.lang.Long.bitCount(hi xor hashes[j]) <= maxDistance) {
                        union(index.getValue(withHash[i].id), index.getValue(withHash[j].id))
                    }
                }
            }
        }

        // 3. Build groups and pick the copy to keep in each.
        val groups = items.indices.groupBy { find(it) }.values.filter { it.size > 1 }.map { members -> members.map { items[it] } }
        val dims = dimensions(groups.flatten())
        groups.map { members ->
            val keep = members.maxWith(keepOrder(dims))
            Group(
                items = listOf(keep) + members.filter { it.id != keep.id },
                suggestedKeep = keep.id,
                sha = members.mapNotNull { m -> sha[m.id]?.let { m.id to it } }.toMap(),
                dhash = members.mapNotNull { m -> dhash[m.id]?.let { m.id to it } }.toMap(),
            )
        }.sortedByDescending { g -> g.items.sumOf { it.size } - g.items.first().size }
    }

    /**
     * Which copy is the best to keep: the most pixels, then the camera's own folder over a
     * messaging app's copy, then the bigger file (less compressed), then the oldest (the original).
     */
    private fun keepOrder(dims: Map<Long, Long>) =
        compareBy<MediaItem> { dims[it.id] ?: 0L }
            .thenBy { if (it.relativePath.startsWith("DCIM", ignoreCase = true)) 1 else 0 }
            .thenBy { if (MESSAGING.any { m -> it.relativePath.contains(m, ignoreCase = true) }) 0 else 1 }
            .thenBy { it.size }
            .thenByDescending { it.takenAt }

    /** Width x height per item, from MediaStore (no file reads). */
    private fun dimensions(items: List<MediaItem>): Map<Long, Long> {
        val out = HashMap<Long, Long>()
        items.chunked(500).forEach { chunk ->
            val ids = chunk.joinToString(",") { it.id.toString() }
            context.contentResolver.query(
                MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL),
                arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.WIDTH, MediaStore.MediaColumns.HEIGHT),
                "${MediaStore.MediaColumns._ID} IN ($ids)", null, null,
            )?.use { c ->
                while (c.moveToNext()) out[c.getLong(0)] = c.getLong(1) * c.getLong(2)
            }
        }
        return out
    }

    private fun sha256(item: MediaItem): String? = runCatching {
        val digest = MessageDigest.getInstance("SHA-256")
        context.contentResolver.openInputStream(item.uri)?.use { input ->
            val buf = ByteArray(256 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        } ?: return null
        digest.digest().joinToString("") { "%02x".format(it) }
    }.getOrNull()

    /**
     * dHash: shrink to 9x8 greyscale, then one bit per pixel for "brighter than its right
     * neighbour". Uses MediaStore's own small thumbnail, so it's quick.
     */
    private fun differenceHash(item: MediaItem): Long? = runCatching {
        val thumb = context.contentResolver.loadThumbnail(item.uri, Size(96, 96), null)
        val small = Bitmap.createScaledBitmap(thumb, 9, 8, true)
        if (small !== thumb) thumb.recycle()
        val px = IntArray(72)
        small.getPixels(px, 0, 9, 0, 0, 9, 8)
        small.recycle()
        fun grey(c: Int) = (c shr 16 and 0xff) * 299 + (c shr 8 and 0xff) * 587 + (c and 0xff) * 114
        var hash = 0L
        var bit = 0
        for (y in 0 until 8) for (x in 0 until 8) {
            if (grey(px[y * 9 + x]) > grey(px[y * 9 + x + 1])) hash = hash or (1L shl bit)
            bit++
        }
        hash
    }.getOrNull()

    companion object {
        /** Folders where messaging apps keep (often re-compressed) copies of photos. */
        val MESSAGING = listOf("WhatsApp", "Telegram", "Messenger", "Signal", "Snapchat", "Viber", "Discord")

        /** Sensitivity for similar photos: bits that may differ (of 64). */
        const val STRICT = 4
        const val NORMAL = 8
        const val LOOSE = 12
    }
}

/** Fingerprints of phone files, so rescans only read what changed. */
private class FingerprintCache(context: Context) :
    SQLiteOpenHelper(context, "fingerprints.db", null, 1) {

    class Entry(val size: Long, val modified: Long, val sha: String?, val dhash: Long?)

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE fp(media_id INTEGER PRIMARY KEY, size INTEGER NOT NULL, modified INTEGER NOT NULL, " +
                "sha TEXT, dhash INTEGER)"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun load(): Map<Long, Entry> {
        val out = HashMap<Long, Entry>()
        readableDatabase.rawQuery("SELECT media_id, size, modified, sha, dhash FROM fp", null).use { c ->
            while (c.moveToNext()) {
                out[c.getLong(0)] = Entry(
                    c.getLong(1), c.getLong(2),
                    c.getString(3), if (c.isNull(4)) null else c.getLong(4),
                )
            }
        }
        return out
    }

    fun putSha(item: MediaItem, sha: String) = put(item, "sha", sha)

    fun putDhash(item: MediaItem, dhash: Long) = put(item, "dhash", dhash)

    /** Sets one fingerprint; a changed file (new size or time) loses its other one. */
    private fun put(item: MediaItem, column: String, value: Any) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val same = db.rawQuery(
                "SELECT 1 FROM fp WHERE media_id = ? AND size = ? AND modified = ?",
                arrayOf(item.id.toString(), item.size.toString(), item.modifiedAt.toString()),
            ).use { it.moveToFirst() }
            if (!same) {
                db.insertWithOnConflict(
                    "fp", null,
                    ContentValues().apply {
                        put("media_id", item.id)
                        put("size", item.size)
                        put("modified", item.modifiedAt)
                    },
                    SQLiteDatabase.CONFLICT_REPLACE,
                )
            }
            val v = ContentValues().apply {
                when (value) {
                    is String -> put(column, value)
                    is Long -> put(column, value)
                }
            }
            db.update("fp", v, "media_id = ?", arrayOf(item.id.toString()))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }
}
