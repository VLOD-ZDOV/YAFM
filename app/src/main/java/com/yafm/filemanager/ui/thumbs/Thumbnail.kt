package com.yafm.filemanager.ui.thumbs

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.os.Build
import coil.ImageLoader
import coil.decode.DataSource
import coil.decode.ImageSource
import coil.fetch.DrawableResult
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.fetch.SourceResult
import coil.request.CachePolicy
import coil.request.ErrorResult
import coil.request.ImageRequest
import coil.request.Options
import coil.request.SuccessResult
import coil.request.videoFrameMillis
import okio.Path.Companion.toOkioPath
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger

/**
 * A file's preview at [size] px, as a Coil model. Previews are kept on disk: Coil caches only
 * downloads there, so without this every photo, video frame and cover was decoded from the file
 * again after leaving the memory cache, and on every start of the app.
 */
data class Thumbnail(val path: String, val lastModified: Long, val audio: Boolean, val size: Int)

class ThumbnailFetcher(
    private val data: Thumbnail,
    private val options: Options,
    private val imageLoader: ImageLoader
) : Fetcher {
    override suspend fun fetch(): FetchResult {
        val cached = ThumbnailCache.file(options.context, data)
        val length = cached.length()
        if (length > 0) {
            return SourceResult(ImageSource(cached.toOkioPath()), "image/webp", DataSource.DISK)
        }
        // An empty file marks a file without a preview (a song without a cover): it is not tried again.
        if (cached.exists()) throw IOException("no preview")

        val request = ImageRequest.Builder(options.context)
            .data(if (data.audio) AudioCover(data.path) else File(data.path))
            .size(data.size)
            .videoFrameMillis(1000)
            .allowHardware(false)
            .memoryCachePolicy(CachePolicy.DISABLED)
            .build()
        return when (val result = imageLoader.execute(request)) {
            is SuccessResult -> {
                (result.drawable as? BitmapDrawable)?.bitmap?.let { ThumbnailCache.save(cached, it) }
                DrawableResult(result.drawable, isSampled = true, dataSource = DataSource.DISK)
            }
            is ErrorResult -> {
                ThumbnailCache.markMissing(cached)
                throw result.throwable
            }
        }
    }

    class Factory : Fetcher.Factory<Thumbnail> {
        override fun create(data: Thumbnail, options: Options, imageLoader: ImageLoader) = ThumbnailFetcher(data, options, imageLoader)
    }
}

/** Previews in the app's cache folder, which Android may also clear when space runs low. */
object ThumbnailCache {

    private const val MAX_BYTES = 64L * 1024 * 1024
    private const val TRIM_EVERY = 100

    private val saves = AtomicInteger()

    fun file(context: Context, thumb: Thumbnail): File {
        val key = "${thumb.path}\u0000${thumb.lastModified}\u0000${thumb.size}"
        val hash = MessageDigest.getInstance("SHA-1").digest(key.toByteArray())
        return File(File(context.cacheDir, "thumbs"), hash.joinToString("") { "%02x".format(it) })
    }

    fun save(target: File, bitmap: Bitmap) = write(target) { out ->
        @Suppress("DEPRECATION")
        val format = if (Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.WEBP
        bitmap.compress(format, 80, out)
    }

    fun markMissing(target: File) = write(target) { }

    // Written aside and renamed, so a half-written preview is never read.
    private fun write(target: File, body: (java.io.OutputStream) -> Unit) {
        runCatching {
            val dir = target.parentFile!!
            dir.mkdirs()
            val tmp = File.createTempFile("thumb", ".tmp", dir)
            try {
                tmp.outputStream().use(body)
                if (!tmp.renameTo(target)) tmp.delete()
            } catch (e: Exception) {
                tmp.delete()
                throw e
            }
            // The first save of each run trims too: a run may end before the hundredth.
            if (saves.getAndIncrement() % TRIM_EVERY == 0) trim(dir)
        }
    }

    /** Oldest previews go first, down to three quarters of the limit. */
    private fun trim(dir: File) {
        val files = dir.listFiles()?.map { Triple(it, it.length(), it.lastModified()) } ?: return
        var total = files.sumOf { it.second }
        if (total <= MAX_BYTES) return
        for ((file, length) in files.sortedBy { it.third }) {
            if (total <= MAX_BYTES * 3 / 4) break
            if (file.delete()) total -= length
        }
    }
}
