package dev.uphotosync.app.smb

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.ConnectivityManager
import android.media.MediaDataSource
import android.media.MediaMetadataRetriever
import coil.disk.DiskCache
import coil.imageLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import dev.uphotosync.app.data.StorageSpace
import okio.buffer
import java.io.File

/** Coil model for a NAS file's thumbnail (photo or video), as shown in the NAS tab's grid and list. */
data class NasThumb(val path: String, val size: Long, val modifiedAt: Long) {
    val isVideo get() = path.substringAfterLast('.', "").lowercase() in NasEntry.VIDEO_EXT
}

/**
 * Makes and caches small thumbnails (JPEG, [SIZE] px) for NAS photos and videos.
 *
 * - At most [MAX_PARALLEL] at once, newest request first ([LifoGate]), so what's on screen loads first.
 * - A thumbnail that has **started** downloading always finishes and is cached, even if its file
 *   scrolled away. One that's only **queued** is dropped once nothing on screen wants it.
 * - [prefetch] fills in the rest of a folder slowly, one at a time, only while nothing on screen
 *   is waiting.
 *
 * Thumbnails go in Coil's disk cache, so the grid then shows them without any network.
 */
class NasThumbnails(private val context: Context, private val nas: NasClient) {

    // Runs downloads independently of the screen that asked, so started ones aren't cut off.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gate = LifoGate(MAX_PARALLEL)

    private val diskCache: DiskCache? get() = context.imageLoader.diskCache

    private class Load {
        lateinit var job: Deferred<Unit>
        @Volatile var started = false
        var waiters = 0
    }

    private val loads = HashMap<String, Load>()

    /**
     * Space the image cache uses and its limit, in bytes: NAS thumbnails, video frames and
     * full-size NAS photos opened in the viewer. Call off the main thread.
     */
    fun cacheUsage(): StorageSpace? = diskCache?.let { StorageSpace(total = it.maxSize, free = it.maxSize - it.size) }

    /**
     * Empties the image cache (and the in-memory one). Thumbnails are simply made again when
     * next needed. Call off the main thread.
     */
    fun clearCache() {
        context.imageLoader.memoryCache?.clear()
        diskCache?.clear()
        // Leftovers from interrupted thumbnail or photo downloads. Only old ones: a recent temp
        // file may belong to a download still running.
        val cutoff = System.currentTimeMillis() - STALE_TEMP_MS
        context.cacheDir.listFiles()?.filter {
            (it.name.startsWith("thumb") || it.name.startsWith("nas")) && it.name.endsWith(".img") && it.lastModified() < cutoff
        }?.forEach { it.delete() }
    }

    fun isCached(thumb: NasThumb): Boolean =
        diskCache?.openSnapshot(cacheKey(thumb))?.use { true } ?: false

    /**
     * Makes sure [thumb] is in the disk cache, waiting for it. If the caller is cancelled (its file
     * scrolled off screen), a download that hasn't started yet is dropped, but one in progress
     * carries on and is cached.
     */
    suspend fun ensureCached(thumb: NasThumb) {
        if (isCached(thumb)) return
        val key = cacheKey(thumb)
        val load = synchronized(loads) {
            (loads[key] ?: newLoad(thumb, key)).also { it.waiters++ }
        }
        try {
            load.job.await()
        } finally {
            synchronized(loads) {
                load.waiters--
                if (load.waiters == 0 && !load.started) {
                    load.job.cancel()
                    if (loads[key] === load) loads.remove(key)
                }
            }
        }
    }

    // Called with [loads] locked.
    private fun newLoad(thumb: NasThumb, key: String): Load {
        val load = Load()
        load.job = scope.async(start = CoroutineStart.LAZY) {
            gate.withPermit {
                // From here on it runs to the end, whoever is still watching.
                load.started = true
                if (!isCached(thumb)) generate(thumb, key)
            }
        }
        load.job.invokeOnCompletion { synchronized(loads) { if (loads[key] === load) loads.remove(key) } }
        loads[key] = load
        load.job.start()
        return load
    }

    /**
     * Slowly fills in thumbnails for [thumbs] (the rest of a folder) while nothing on screen is
     * waiting, one at a time. Cancel the returned job when leaving the folder; a thumbnail that's
     * already downloading still finishes.
     */
    fun prefetch(thumbs: List<NasThumb>, owner: CoroutineScope): Job = owner.launch(Dispatchers.IO) {
        // Only on Wi-Fi or other unmetered networks, and only so many per folder: a big folder
        // over mobile data or a VPN could otherwise pull many GB just by being open.
        for (thumb in thumbs.take(MAX_PREFETCH)) {
            gate.awaitIdle() // what's on screen comes first
            if (onMeteredNetwork()) break
            if (isCached(thumb)) continue
            runCatching { ensureCached(thumb) }
            delay(PREFETCH_PAUSE_MS)
        }
    }

    private fun onMeteredNetwork(): Boolean =
        context.getSystemService(ConnectivityManager::class.java).isActiveNetworkMetered

    private suspend fun generate(thumb: NasThumb, key: String) {
        val bitmap = if (thumb.isVideo) videoFrame(thumb) else photoThumbnail(thumb)
        val cache = diskCache ?: return
        val editor = cache.openEditor(key) ?: return
        try {
            cache.fileSystem.sink(editor.data).buffer().use { sink ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, sink.outputStream())
            }
            editor.commit()
        } catch (e: Throwable) {
            runCatching { editor.abort() }
            throw e
        } finally {
            bitmap.recycle()
        }
    }

    /** Downloads the photo to a temp file and shrinks it, keeping its rotation. */
    private suspend fun photoThumbnail(thumb: NasThumb): Bitmap {
        val tmp = File.createTempFile("thumb", ".img", context.cacheDir)
        try {
            tmp.outputStream().use { nas.download(thumb.path, it) }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(tmp.path, bounds)
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= SIZE && bounds.outHeight / (sample * 2) >= SIZE) sample *= 2
            val decoded = BitmapFactory.decodeFile(tmp.path, BitmapFactory.Options().apply { inSampleSize = sample })
                ?: error("Can't read ${thumb.path} as an image")
            val rotation = runCatching {
                when (ExifInterface(tmp.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270
                    else -> 0
                }
            }.getOrDefault(0)
            return scaleAndRotate(decoded, rotation)
        } finally {
            tmp.delete()
        }
    }

    /** A frame from about a second in, reading only the parts of the video it needs. */
    private suspend fun videoFrame(thumb: NasThumb): Bitmap = nas.openReader(thumb.path).use { reader ->
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(object : MediaDataSource() {
                override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int =
                    if (size == 0) 0 else reader.read(position, buffer, offset, size)

                override fun getSize(): Long = reader.size
                override fun close() = Unit
            })
            retriever.getScaledFrameAtTime(FRAME_AT_US, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, SIZE, SIZE)
                ?: retriever.frameAtTime ?: error("No frame in ${thumb.path}")
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun scaleAndRotate(src: Bitmap, rotation: Int): Bitmap {
        val scale = minOf(1f, SIZE.toFloat() / maxOf(src.width, src.height))
        if (scale == 1f && rotation == 0) return src
        val m = Matrix().apply {
            postScale(scale, scale)
            if (rotation != 0) postRotate(rotation.toFloat())
        }
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true).also { if (it !== src) src.recycle() }
    }

    companion object {
        /** Thumbnails loading at once: enough to fill the screen quickly without flooding the NAS or memory. */
        private const val MAX_PARALLEL = 3
        /** Longest side of a cached thumbnail, in pixels. */
        const val SIZE = 512
        private const val JPEG_QUALITY = 85
        private const val FRAME_AT_US = 1_000_000L
        private const val STALE_TEMP_MS = 60 * 60 * 1000L
        /** Most thumbnails filled in per folder in the background; visible ones always load. */
        private const val MAX_PREFETCH = 1000
        /** Breather between background thumbnails, so filling in a folder stays gentle. */
        private const val PREFETCH_PAUSE_MS = 150L

        fun cacheKey(thumb: NasThumb) = "nasthumb:${thumb.path}:${thumb.size}:${thumb.modifiedAt}"
    }
}
