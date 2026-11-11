package dev.uphotosync.app.smb

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import kotlinx.coroutines.runBlocking

/**
 * Lets the video player stream a NAS file over SMB, reading only the parts it needs, so
 * playback starts at once and seeking jumps straight to the right place; nothing is downloaded
 * first. ExoPlayer calls this on its own loading thread, hence the blocking calls.
 */
@UnstableApi
class NasDataSource(private val nas: NasClient) : BaseDataSource(/* isNetwork = */ true) {
    private var reader: RemoteReader? = null
    private var uri: Uri? = null
    private var position = 0L
    private var remaining = 0L
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        transferInitializing(dataSpec)
        val path = pathOf(dataSpec.uri) ?: throw DataSourceException(PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND)
        val r = try {
            runBlocking { nas.openReader(path) }
        } catch (e: Exception) {
            throw DataSourceException(e, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)
        }
        reader = r
        position = dataSpec.position
        if (position > r.size) throw DataSourceException(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE)
        remaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) dataSpec.length else r.size - position
        opened = true
        transferStarted(dataSpec)
        return remaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining == 0L) return C.RESULT_END_OF_INPUT
        val n = try {
            reader!!.read(position, buffer, offset, minOf(length.toLong(), remaining).toInt())
        } catch (e: Exception) {
            throw DataSourceException(e, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)
        }
        if (n <= 0) return C.RESULT_END_OF_INPUT
        position += n
        remaining -= n
        bytesTransferred(n)
        return n
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        uri = null
        reader?.close()
        reader = null
        if (opened) {
            opened = false
            transferEnded()
        }
    }

    class Factory(private val nas: NasClient) : DataSource.Factory {
        override fun createDataSource(): DataSource = NasDataSource(nas)
    }

    companion object {
        private const val SCHEME = "nas"

        /** A player Uri for a file on the NAS share. */
        fun uriFor(path: String): Uri = Uri.Builder().scheme(SCHEME).authority("share").appendQueryParameter("p", path).build()

        private fun pathOf(uri: Uri): String? = uri.takeIf { it.scheme == SCHEME }?.getQueryParameter("p")
    }
}
