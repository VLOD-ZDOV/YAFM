package com.yafm.filemanager.data.incoming

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** A file another app asked to open with this one. [isCopy]: only a copy in the cache could be had. */
data class IncomingFile(val file: File, val isCopy: Boolean)

object Incoming {

    /** The file behind [uri]: its real path when it can be read there, else a copy in the cache. */
    suspend fun resolve(context: Context, uri: Uri): IncomingFile = withContext(Dispatchers.IO) {
        if (uri.scheme == "file") uri.path?.let { File(it) }?.takeIf { it.canRead() }?.let { return@withContext IncomingFile(it, false) }
        realPath(context, uri)?.let { return@withContext IncomingFile(it, false) }
        val name = displayName(context, uri)?.replace('/', '_')?.takeIf { it.isNotBlank() && it != "." && it != ".." } ?: "file"
        val dir = File(context.cacheDir, "incoming").apply { deleteRecursively(); mkdirs() }
        val copy = File(dir, name)
        context.contentResolver.openInputStream(uri)?.use { input -> copy.outputStream().use { input.copyTo(it) } }
            ?: throw java.io.IOException("Cannot read $uri")
        IncomingFile(copy, true)
    }

    // Media and download providers still tell the path; with all files access it can be read directly.
    private fun realPath(context: Context, uri: Uri): File? = runCatching {
        @Suppress("DEPRECATION")
        context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0)?.let { File(it) }?.takeIf { it.isFile && it.canRead() } else null
        }
    }.getOrNull()

    private fun displayName(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/')
}
