package com.yafm.filemanager.data.incoming

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import com.yafm.filemanager.R
import com.yafm.filemanager.str
import java.io.IOException
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** A file another app asked to open with this one. [isCopy]: only a copy in the cache could be had. */
data class IncomingFile(val file: File, val isCopy: Boolean)

object Incoming {

    /** The file behind [uri]: its real path when it can be read there, else a copy in the cache. */
    suspend fun resolve(context: Context, uri: Uri): IncomingFile = withContext(Dispatchers.IO) {
        // Any app can send this intent: it must not get this app to open its own private files
        // (saved servers, the qsh key) and pass them on. Only shared storage and other apps' content.
        when (uri.scheme) {
            "file" -> {
                val file = uri.path?.let { File(it) }?.takeIf { it.canRead() && isShared(it) }
                    ?: throw IOException(str(R.string.cannot_open_this_file))
                return@withContext IncomingFile(file, false)
            }
            "content" -> if (uri.authority == "${context.packageName}.fileprovider") throw IOException(str(R.string.cannot_open_this_file))
            else -> throw IOException(str(R.string.cannot_open_this_file))
        }
        realPath(context, uri)?.let { return@withContext IncomingFile(it, false) }
        val name = displayName(context, uri)?.replace('/', '_')?.takeIf { it.isNotBlank() && it != "." && it != ".." } ?: "file"
        val dir = File(context.cacheDir, "incoming").apply { deleteRecursively(); mkdirs() }
        val copy = File(dir, name)
        context.contentResolver.openInputStream(uri)?.use { input -> copy.outputStream().use { input.copyTo(it) } }
            ?: throw IOException(str(R.string.cannot_open_this_file))
        IncomingFile(copy, true)
    }

    // Media and download providers still tell the path; with all files access it can be read directly.
    private fun realPath(context: Context, uri: Uri): File? = runCatching {
        @Suppress("DEPRECATION")
        context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0)?.let { File(it) }?.takeIf { it.isFile && it.canRead() && isShared(it) } else null
        }
    }.getOrNull()

    /** Inside shared storage, where every file app sees it anyway; links are resolved first. */
    private fun isShared(file: File): Boolean {
        val shared = Environment.getExternalStorageDirectory().canonicalPath + File.separator
        return runCatching { file.canonicalPath.startsWith(shared) }.getOrDefault(false)
    }

    private fun displayName(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/')
}
