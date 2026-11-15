package dev.uphotosync.app.data

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.provider.MediaStore.Files.FileColumns
import androidx.annotation.RequiresApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A photo or video in Android's trash. */
data class TrashedItem(
    val id: Long,
    val uri: Uri,
    val name: String,
    val size: Long,
    val isVideo: Boolean,
    /** When Android deletes it for good (epoch millis). */
    val expiresAt: Long,
)

/** Android's media trash (Android 11+), which the system gallery doesn't always show. */
object PhoneTrash {

    @RequiresApi(Build.VERSION_CODES.R)
    suspend fun load(context: Context): List<TrashedItem> = withContext(Dispatchers.IO) {
        val args = Bundle().apply {
            putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_ONLY)
            putString(
                android.content.ContentResolver.QUERY_ARG_SQL_SELECTION,
                "${FileColumns.MEDIA_TYPE} IN (${FileColumns.MEDIA_TYPE_IMAGE}, ${FileColumns.MEDIA_TYPE_VIDEO})",
            )
        }
        val projection = arrayOf(
            FileColumns._ID, FileColumns.DISPLAY_NAME, FileColumns.SIZE,
            FileColumns.MEDIA_TYPE, FileColumns.DATE_EXPIRES,
        )
        val out = ArrayList<TrashedItem>()
        context.contentResolver.query(
            MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL), projection, args, null,
        )?.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                val isVideo = c.getInt(3) == FileColumns.MEDIA_TYPE_VIDEO
                val base = if (isVideo) MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                out += TrashedItem(
                    id = id,
                    uri = ContentUris.withAppendedId(base, id),
                    name = c.getString(1) ?: "media_$id",
                    size = c.getLong(2),
                    isVideo = isVideo,
                    expiresAt = c.getLong(4) * 1000,
                )
            }
        }
        // Soonest to go first.
        out.sortedBy { it.expiresAt }
    }
}
