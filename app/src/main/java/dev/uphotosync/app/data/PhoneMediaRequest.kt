package dev.uphotosync.app.data

import android.app.RecoverableSecurityException
import android.content.Context
import android.content.IntentSender
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Deletes, trashes or restores phone media. Android only lets an app change other apps'
 * photos with the user's OK, so each batch goes through a system prompt: one prompt for
 * the whole batch on Android 11+, one per file on Android 10. One batch at a time.
 */
class PhoneMediaRequest(private val context: Context) {

    enum class Action {
        /** Gone for good. */
        Delete,

        /** Into Android's trash, restorable for 30 days (Android 11+). */
        Trash,

        /** Back out of Android's trash (Android 11+). */
        Restore,
    }

    data class Target(val id: Long, val uri: Uri)

    /** True from [start] until [finish]; a second batch would be mistaken for the first. */
    var busy = false
        private set

    var action = Action.Delete
        private set

    private var batch: List<Long> = emptyList()
    private var pending: List<Target> = emptyList()
    private val done = ArrayList<Long>()

    /**
     * Starts a batch. Returns Android's prompt to show, or null if it finished without one.
     * Android 10 has no trash, so there every action is a plain delete.
     */
    suspend fun start(targets: List<Target>, action: Action): IntentSender? {
        if (busy || targets.isEmpty()) return null
        busy = true
        batch = targets.map { it.id }
        pending = targets
        done.clear()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            this.action = Action.Delete
            return continueDelete()
        }
        this.action = action
        val resolver = context.contentResolver
        val uris = targets.map { it.uri }
        return when (action) {
            Action.Delete -> MediaStore.createDeleteRequest(resolver, uris)
            Action.Trash -> MediaStore.createTrashRequest(resolver, uris, true)
            Action.Restore -> MediaStore.createTrashRequest(resolver, uris, false)
        }.intentSender
    }

    /**
     * Android 10: deletes one by one. For a photo the app didn't create, Android asks first;
     * returns that prompt, and is called again once it's been allowed.
     */
    suspend fun continueDelete(): IntentSender? = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        while (pending.isNotEmpty()) {
            val t = pending.first()
            try {
                resolver.delete(t.uri, null, null)
                done += t.id
            } catch (e: RecoverableSecurityException) {
                return@withContext e.userAction.actionIntent.intentSender
            } catch (e: Exception) {
                // Already gone, or the provider refused outright: skip it rather than crash.
            }
            pending = pending.drop(1)
        }
        null
    }

    /**
     * Ends the batch and returns the ids that were acted on. On Android 11+ the system did the
     * work, so the whole batch counts if [confirmedBatch], none if it was cancelled.
     */
    fun finish(confirmedBatch: Boolean): List<Long> {
        val ids = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (confirmedBatch) batch else emptyList()
        } else {
            done.toList()
        }
        batch = emptyList()
        pending = emptyList()
        done.clear()
        busy = false
        return ids
    }
}
