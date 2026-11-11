package dev.uphotosync.app.data

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import dev.uphotosync.app.smb.NasClient
import dev.uphotosync.app.smb.NasEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.FilterOutputStream
import java.io.OutputStream

/** Copies photos and videos from the NAS back into the phone's gallery ("Save to phone"). */
class Restorer(
    private val context: Context,
    private val nas: NasClient,
    private val index: BackupIndex,
    private val settings: SettingsStore,
) {
    /**
     * Saves [entry] into its original phone folder when possible (e.g. DCIM/Camera), else
     * Pictures/ or Movies/uPhotoSync. [onBytes] reports bytes written so far and is called
     * from the IO thread.
     */
    suspend fun restore(entry: NasEntry, onBytes: (Long) -> Unit = {}) = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(entry.extension)
            ?: if (entry.isVideo) "video/mp4" else "image/jpeg"
        val collection = if (entry.isVideo) MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        else MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        fun values(folder: String) = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, entry.name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, folder)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val fallback = (if (entry.isVideo) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES) + "/uPhotoSync"
        val uri = phoneFolderFor(entry)?.let { runCatching { resolver.insert(collection, values(it)) }.getOrNull() }
            ?: resolver.insert(collection, values(fallback))
            ?: error("Couldn't create the file")
        try {
            resolver.openOutputStream(uri)?.use { out -> nas.download(entry.path, CountingStream(out, onBytes)) }
                ?: error("Couldn't write the file")
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            // It came from the NAS, so it's already backed up; don't upload it again.
            index.mark(ContentUris.parseId(uri), entry.path, entry.size)
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
    }

    /**
     * The phone folder a NAS file was backed up from: its folder relative to the backup
     * destination (destination\DCIM\Camera\x.jpg -> "DCIM/Camera"). Null if it's outside the
     * destination or not under a top-level folder Android accepts for that media type.
     */
    private suspend fun phoneFolderFor(entry: NasEntry): String? {
        val destination = NasPath.clean(settings.config.first()?.destination.orEmpty())
        val dir = NasPath.parent(entry.path)
        val relative = when {
            destination.isEmpty() -> dir
            dir.lowercase().startsWith(destination.lowercase() + "\\") -> dir.substring(destination.length + 1)
            else -> return null
        }
        val parts = relative.split('\\').filter { it.isNotEmpty() }
        val allowed = if (entry.isVideo) {
            listOf(Environment.DIRECTORY_DCIM, Environment.DIRECTORY_MOVIES, Environment.DIRECTORY_PICTURES)
        } else {
            listOf(Environment.DIRECTORY_DCIM, Environment.DIRECTORY_PICTURES)
        }
        val top = allowed.firstOrNull { it.equals(parts.firstOrNull(), ignoreCase = true) } ?: return null
        return (listOf(top) + parts.drop(1)).joinToString("/")
    }

    private class CountingStream(out: OutputStream, private val onBytes: (Long) -> Unit) : FilterOutputStream(out) {
        private var count = 0L
        override fun write(b: ByteArray, off: Int, len: Int) {
            out.write(b, off, len)
            count += len
            onBytes(count)
        }
        override fun write(b: Int) {
            out.write(b)
            onBytes(++count)
        }
    }
}
