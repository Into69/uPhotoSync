package dev.uphotosync.app.smb

import coil.ImageLoader
import coil.decode.DataSource
import coil.decode.ImageSource
import coil.disk.DiskCache
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.fetch.SourceResult
import coil.key.Keyer
import coil.request.Options
import okio.FileSystem
import okio.Path.Companion.toOkioPath
import okio.buffer

/** Coil model for a NAS photo at full size (the full-screen viewer). Thumbnails use [NasThumb]. */
data class NasFile(val path: String, val size: Long, val modifiedAt: Long)

private fun fromSnapshot(snapshot: DiskCache.Snapshot, cache: DiskCache, key: String, source: DataSource) = SourceResult(
    // Closing the source closes the snapshot, releasing the cache entry.
    source = ImageSource(snapshot.data, cache.fileSystem, key, snapshot),
    mimeType = null,
    dataSource = source,
)

/** Loads [NasThumb]s: from the disk cache, or made by [NasThumbnails] (queued, visible first). */
class NasThumbFetcher(
    private val thumb: NasThumb,
    private val thumbnails: NasThumbnails,
    private val diskCache: DiskCache?,
) : Fetcher {
    override suspend fun fetch(): FetchResult {
        val cache = diskCache ?: error("No image cache")
        val key = NasThumbnails.cacheKey(thumb)
        cache.openSnapshot(key)?.let { return fromSnapshot(it, cache, key, DataSource.DISK) }
        thumbnails.ensureCached(thumb)
        val snapshot = cache.openSnapshot(key) ?: error("Couldn't make a thumbnail for ${thumb.path}")
        return fromSnapshot(snapshot, cache, key, DataSource.NETWORK)
    }

    class Factory(private val thumbnails: NasThumbnails) : Fetcher.Factory<NasThumb> {
        override fun create(data: NasThumb, options: Options, imageLoader: ImageLoader): Fetcher =
            NasThumbFetcher(data, thumbnails, imageLoader.diskCache)
    }
}

class NasThumbKeyer : Keyer<NasThumb> {
    override fun key(data: NasThumb, options: Options) = NasThumbnails.cacheKey(data)
}

/**
 * Loads a NAS photo at full size for the viewer, streamed into Coil's disk cache rather than
 * memory. One photo at a time is on screen, so there's no queue here.
 */
class NasFileFetcher(
    private val file: NasFile,
    private val nas: NasClient,
    private val options: Options,
    private val diskCache: DiskCache?,
) : Fetcher {

    private val key get() = cacheKey(file)

    override suspend fun fetch(): FetchResult {
        val cache = diskCache
        cache?.openSnapshot(key)?.let { return fromSnapshot(it, cache, key, DataSource.DISK) }
        val editor = cache?.openEditor(key)
        if (cache != null && editor != null) {
            try {
                cache.fileSystem.sink(editor.data).buffer().use { sink -> nas.download(file.path, sink.outputStream()) }
                val snapshot = editor.commitAndOpenSnapshot() ?: error("Couldn't cache ${file.path}")
                return fromSnapshot(snapshot, cache, key, DataSource.NETWORK)
            } catch (e: Throwable) {
                runCatching { editor.abort() }
                throw e
            }
        }
        // No disk cache (or the entry is busy): a temporary file, deleted once Coil has decoded it.
        val tmp = java.io.File.createTempFile("nas", ".img", options.context.cacheDir)
        try {
            tmp.outputStream().use { nas.download(file.path, it) }
        } catch (e: Throwable) {
            tmp.delete()
            throw e
        }
        return SourceResult(
            source = ImageSource(tmp.toOkioPath(), FileSystem.SYSTEM, null, { tmp.delete() }),
            mimeType = null,
            dataSource = DataSource.NETWORK,
        )
    }

    class Factory(private val nas: NasClient) : Fetcher.Factory<NasFile> {
        override fun create(data: NasFile, options: Options, imageLoader: ImageLoader): Fetcher =
            NasFileFetcher(data, nas, options, imageLoader.diskCache)
    }

    companion object {
        fun cacheKey(file: NasFile) = "nas:${file.path}:${file.size}:${file.modifiedAt}"
    }
}

class NasFileKeyer : Keyer<NasFile> {
    override fun key(data: NasFile, options: Options) = NasFileFetcher.cacheKey(data)
}
