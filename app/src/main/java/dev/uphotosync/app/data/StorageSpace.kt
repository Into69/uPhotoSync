package dev.uphotosync.app.data

import android.app.usage.StorageStatsManager
import android.content.Context
import android.os.Environment
import android.os.StatFs
import android.os.storage.StorageManager

/** Total and free space of a disk, in bytes. */
data class StorageSpace(val total: Long, val free: Long) {
    val used get() = (total - free).coerceAtLeast(0)
    val usedFraction get() = if (total > 0) (used.toFloat() / total).coerceIn(0f, 1f) else 0f

    /** Less than [LOW_FRACTION] of the space left. */
    val low get() = total > 0 && free.toDouble() / total < LOW_FRACTION

    companion object {
        const val LOW_FRACTION = 0.10
    }
}

object PhoneStorage {
    /**
     * The phone's internal storage, with the same numbers the Settings app shows. No permission
     * is needed. Call off the main thread (it asks a system service).
     */
    fun read(context: Context): StorageSpace? = runCatching {
        val stats = context.getSystemService(StorageStatsManager::class.java)
        StorageSpace(
            total = stats.getTotalBytes(StorageManager.UUID_DEFAULT),
            free = stats.getFreeBytes(StorageManager.UUID_DEFAULT),
        )
    }.recoverCatching {
        // Fallback: the data partition, where photos live.
        val fs = StatFs(Environment.getDataDirectory().path)
        StorageSpace(total = fs.totalBytes, free = fs.availableBytes)
    }.getOrNull()
}
