package com.nexus.filemanager

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.VideoFrameDecoder
import com.nexus.filemanager.data.repository.FileRepository
import com.nexus.filemanager.data.settings.SettingsRepository

class YafmApp : Application(), ImageLoaderFactory {
    val repository by lazy { FileRepository(this) }
    val settings by lazy { SettingsRepository(this) }

    // Video frames as thumbnails, next to Coil's image decoders.
    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .components { add(VideoFrameDecoder.Factory()) }
        .crossfade(true)
        .build()
}
