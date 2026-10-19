package com.yafm.filemanager

import android.app.Application
import androidx.annotation.StringRes
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.VideoFrameDecoder
import com.yafm.filemanager.ui.thumbs.AudioCoverFetcher
import com.yafm.filemanager.data.repository.FileRepository
import com.yafm.filemanager.data.settings.SettingsRepository

/** A string resource from anywhere (view models, file systems, error messages). */
fun str(@StringRes id: Int, vararg args: Any?): String {
    // No app in JVM unit tests: messages then carry the resource id.
    if (!YafmApp.ready) return "string#$id"
    return if (args.isEmpty()) YafmApp.instance.getString(id) else YafmApp.instance.getString(id, *args)
}

class YafmApp : Application(), ImageLoaderFactory {
    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    companion object {
        lateinit var instance: YafmApp
            private set

        val ready: Boolean get() = ::instance.isInitialized
    }

    val repository by lazy { FileRepository(this) }
    val settings by lazy { SettingsRepository(this) }

    // Video frames as thumbnails, next to Coil's image decoders.
    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .components {
            add(VideoFrameDecoder.Factory())
            add(AudioCoverFetcher.Factory())
        }
        .crossfade(true)
        .build()
}
