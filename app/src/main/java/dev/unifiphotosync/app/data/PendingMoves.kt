package dev.unifiphotosync.app.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * Files from "Move to NAS" whose NAS copy has been verified and which are waiting for the
 * user to confirm deleting them from the phone. Android only lets the app delete with the
 * user's OK in the foreground, so this outlives the background job, and the app too.
 *
 * Each id is stored with the phone file's modified time at verification, so a file edited
 * since then is no longer offered for deletion.
 */
class PendingMoves(context: Context) {
    private val file = File(context.noBackupFilesDir, "pending_moves.txt")
    private val _verified = MutableStateFlow(load())

    /** media id -> the file's modified time when its NAS copy was verified. */
    val verified: StateFlow<Map<Long, Long>> = _verified.asStateFlow()

    private fun load(): Map<Long, Long> = runCatching {
        file.readLines().mapNotNull { line ->
            val parts = line.split(':')
            val id = parts.getOrNull(0)?.toLongOrNull() ?: return@mapNotNull null
            // Older entries without a time can't be re-checked; 0 makes them never match, so
            // they're dropped rather than risk deleting a changed file.
            id to (parts.getOrNull(1)?.toLongOrNull() ?: 0L)
        }.toMap()
    }.getOrDefault(emptyMap())

    private fun save(map: Map<Long, Long>) {
        runCatching { file.writeText(map.entries.joinToString("\n") { "${it.key}:${it.value}" }) }
        _verified.value = map
    }

    @Synchronized
    fun add(verified: Map<Long, Long>) = save(_verified.value + verified)

    @Synchronized
    fun remove(ids: Collection<Long>) {
        if (ids.isEmpty() || ids.none { it in _verified.value }) return
        save(_verified.value - ids.toSet())
    }

    @Synchronized
    fun clear() = save(emptyMap())
}
