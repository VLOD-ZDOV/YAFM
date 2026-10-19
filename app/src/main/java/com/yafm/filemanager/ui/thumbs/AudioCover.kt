package com.yafm.filemanager.ui.thumbs

import android.media.MediaMetadataRetriever
import coil.ImageLoader
import coil.decode.DataSource
import coil.decode.ImageSource
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.fetch.SourceResult
import coil.request.Options
import okio.Buffer
import java.io.IOException

/** Cover art embedded in an audio file, as a Coil model. */
data class AudioCover(val path: String)

class AudioCoverFetcher(private val data: AudioCover, private val options: Options) : Fetcher {
    override suspend fun fetch(): FetchResult {
        val bytes = MediaMetadataRetriever().run {
            try {
                setDataSource(data.path)
                embeddedPicture
            } finally {
                release()
            }
        } ?: throw IOException("no cover art")
        return SourceResult(
            source = ImageSource(Buffer().write(bytes), options.context),
            mimeType = null,
            dataSource = DataSource.DISK
        )
    }

    class Factory : Fetcher.Factory<AudioCover> {
        override fun create(data: AudioCover, options: Options, imageLoader: ImageLoader) = AudioCoverFetcher(data, options)
    }
}
