package dev.unifiphotosync.app.data

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.provider.MediaStore.Files.FileColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class MediaItem(
    val id: Long,
    val uri: Uri,
    val name: String,
    val mimeType: String,
    val size: Long,
    /** Capture time in epoch millis (falls back to the time it was added). */
    val takenAt: Long,
    val bucketId: String,
    val bucketName: String,
    /** Folder on the phone, e.g. "DCIM/Camera"; backups mirror it on the NAS. */
    val relativePath: String,
    val isVideo: Boolean,
)

data class Album(val id: String, val name: String, val count: Int)

class MediaRepository(private val context: Context) {

    /** Every photo and video on shared storage, newest first. */
    suspend fun loadAll(): List<MediaItem> = withContext(Dispatchers.IO) {
        val projection = arrayOf(
            FileColumns._ID,
            FileColumns.DISPLAY_NAME,
            FileColumns.MIME_TYPE,
            FileColumns.SIZE,
            FileColumns.DATE_TAKEN,
            FileColumns.DATE_ADDED,
            FileColumns.BUCKET_ID,
            FileColumns.BUCKET_DISPLAY_NAME,
            FileColumns.RELATIVE_PATH,
            FileColumns.MEDIA_TYPE,
        )
        val selection = "${FileColumns.MEDIA_TYPE} IN (?, ?)"
        val args = arrayOf(
            FileColumns.MEDIA_TYPE_IMAGE.toString(),
            FileColumns.MEDIA_TYPE_VIDEO.toString(),
        )
        val out = ArrayList<MediaItem>()
        context.contentResolver.query(
            MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL),
            projection, selection, args, null,
        )?.use { c ->
            val iId = c.getColumnIndexOrThrow(FileColumns._ID)
            val iName = c.getColumnIndexOrThrow(FileColumns.DISPLAY_NAME)
            val iMime = c.getColumnIndexOrThrow(FileColumns.MIME_TYPE)
            val iSize = c.getColumnIndexOrThrow(FileColumns.SIZE)
            val iTaken = c.getColumnIndexOrThrow(FileColumns.DATE_TAKEN)
            val iAdded = c.getColumnIndexOrThrow(FileColumns.DATE_ADDED)
            val iBucket = c.getColumnIndexOrThrow(FileColumns.BUCKET_ID)
            val iBucketName = c.getColumnIndexOrThrow(FileColumns.BUCKET_DISPLAY_NAME)
            val iRelPath = c.getColumnIndexOrThrow(FileColumns.RELATIVE_PATH)
            val iType = c.getColumnIndexOrThrow(FileColumns.MEDIA_TYPE)
            while (c.moveToNext()) {
                val id = c.getLong(iId)
                val isVideo = c.getInt(iType) == FileColumns.MEDIA_TYPE_VIDEO
                val base = if (isVideo) MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                val taken = c.getLong(iTaken).takeIf { it > 0 } ?: (c.getLong(iAdded) * 1000)
                out += MediaItem(
                    id = id,
                    uri = ContentUris.withAppendedId(base, id),
                    name = c.getString(iName) ?: "media_$id",
                    mimeType = c.getString(iMime) ?: if (isVideo) "video/*" else "image/*",
                    size = c.getLong(iSize),
                    takenAt = taken,
                    bucketId = c.getString(iBucket) ?: "",
                    bucketName = c.getString(iBucketName) ?: "Other",
                    relativePath = c.getString(iRelPath)?.trim('/')?.takeIf { it.isNotBlank() }
                        ?: c.getString(iBucketName) ?: "Other",
                    isVideo = isVideo,
                )
            }
        }
        out.sortByDescending { it.takenAt }
        out
    }

    fun albumsOf(items: List<MediaItem>): List<Album> =
        items.groupBy { it.bucketId }
            .map { (id, list) -> Album(id, list.first().bucketName, list.size) }
            .sortedByDescending { it.count }
}
