package dev.unifiphotosync.app.smb

import coil.ImageLoader
import coil.decode.DataSource
import coil.decode.ImageSource
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.fetch.SourceResult
import coil.key.Keyer
import coil.request.Options
import okio.Buffer

/** Coil model for an image stored on the NAS. */
data class NasFile(val path: String, val size: Long, val modifiedAt: Long)

/** Lets Coil load [NasFile]s by reading them over SMB. */
class NasFileFetcher(
    private val file: NasFile,
    private val nas: NasClient,
    private val options: Options,
) : Fetcher {
    override suspend fun fetch(): FetchResult {
        val buffer = Buffer()
        nas.download(file.path, buffer.outputStream())
        return SourceResult(
            source = ImageSource(buffer, options.context),
            mimeType = null,
            dataSource = DataSource.NETWORK,
        )
    }

    class Factory(private val nas: NasClient) : Fetcher.Factory<NasFile> {
        override fun create(data: NasFile, options: Options, imageLoader: ImageLoader): Fetcher =
            NasFileFetcher(data, nas, options)
    }
}

class NasFileKeyer : Keyer<NasFile> {
    override fun key(data: NasFile, options: Options) = "nas:${data.path}:${data.size}:${data.modifiedAt}"
}
