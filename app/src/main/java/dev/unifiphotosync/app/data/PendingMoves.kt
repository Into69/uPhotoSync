package dev.unifiphotosync.app.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * Media ids from "Move to NAS" whose NAS copy has been verified and which are waiting
 * for the user to confirm deleting them from the phone. Android only lets the app
 * delete with the user's OK in the foreground, so this outlives the background job,
 * and the app too.
 */
class PendingMoves(context: Context) {
    private val file = File(context.noBackupFilesDir, "pending_moves.txt")
    private val _ids = MutableStateFlow(load())
    val ids: StateFlow<Set<Long>> = _ids.asStateFlow()

    private fun load(): Set<Long> =
        runCatching { file.readLines().mapNotNull { it.toLongOrNull() }.toSet() }.getOrDefault(emptySet())

    private fun save(ids: Set<Long>) {
        runCatching { file.writeText(ids.joinToString("\n")) }
        _ids.value = ids
    }

    @Synchronized
    fun add(ids: Collection<Long>) = save(_ids.value + ids)

    @Synchronized
    fun remove(ids: Collection<Long>) = save(_ids.value - ids.toSet())

    @Synchronized
    fun clear() = save(emptySet())
}
