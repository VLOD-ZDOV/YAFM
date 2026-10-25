package com.yafm.filemanager.data.media

import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import com.yafm.filemanager.data.model.FileCategory
import com.yafm.filemanager.data.model.FileItem
import com.yafm.filemanager.data.model.StorageType
import java.io.File

/** All files of a kind in internal storage, from Android's media index instead of walking the disk. */
class MediaIndex(private val context: Context) {

    private val home = Environment.getExternalStorageDirectory().absolutePath
    private val homeCanonical = File(home).canonicalPath

    fun query(category: FileCategory): List<FileItem> {
        if (category == FileCategory.RECENT) return recent()
        val (selection, args) = when (category) {
            FileCategory.IMAGE -> mediaType(MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE)
            FileCategory.VIDEO -> mediaType(MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO)
            FileCategory.AUDIO -> mediaType(MediaStore.Files.FileColumns.MEDIA_TYPE_AUDIO)
            else -> {
                // Documents and archives have no media type: matched by extension like FileItem.category.
                val exts = EXTENSIONS[category] ?: return emptyList()
                exts.joinToString(" OR ") { "${MediaStore.Files.FileColumns.DATA} LIKE ?" } to exts.map { "%.$it" }.toTypedArray()
            }
        }
        val projection = arrayOf(
            MediaStore.Files.FileColumns.DATA,
            MediaStore.Files.FileColumns.SIZE,
            MediaStore.Files.FileColumns.DATE_MODIFIED
        )
        val items = mutableListOf<FileItem>()
        context.contentResolver.query(
            MediaStore.Files.getContentUri("external"),
            projection,
            "(${selection}) AND ${MediaStore.Files.FileColumns.DATA} LIKE ?",
            args + "$home/%",
            "${MediaStore.Files.FileColumns.DATE_MODIFIED} DESC"
        )?.use { c ->
            while (c.moveToNext()) {
                val raw = c.getString(0) ?: continue
                // Index rows are not trusted: "home/../.." would pass the LIKE above. Only real files
                // inside internal storage count (the index may also still list deleted ones).
                val file = runCatching { File(raw).canonicalFile }.getOrNull() ?: continue
                val path = file.path
                if (!path.startsWith(homeCanonical + File.separator) || !file.isFile) continue
                items += FileItem(
                    name = file.name,
                    path = path,
                    size = c.getLong(1),
                    lastModified = c.getLong(2) * 1000L,
                    storageType = StorageType.LOCAL
                )
            }
        }
        return items
    }

    /**
     * Files added or changed in the last [days] days, newest first: downloads, saved pictures, edits.
     * Hidden files and app folders (Android/data...) are left out; at most [limit] files.
     */
    private fun recent(days: Int = 30, limit: Int = 1000): List<FileItem> {
        val since = System.currentTimeMillis() / 1000 - days * 86_400L
        val items = mutableListOf<FileItem>()
        context.contentResolver.query(
            MediaStore.Files.getContentUri("external"),
            arrayOf(
                MediaStore.Files.FileColumns.DATA, MediaStore.Files.FileColumns.SIZE,
                MediaStore.Files.FileColumns.DATE_MODIFIED, MediaStore.Files.FileColumns.DATE_ADDED
            ),
            // A folder has no MIME type in the index.
            "${MediaStore.Files.FileColumns.DATA} LIKE ? AND ${MediaStore.Files.FileColumns.MIME_TYPE} IS NOT NULL AND " +
                "(${MediaStore.Files.FileColumns.DATE_MODIFIED} > ? OR ${MediaStore.Files.FileColumns.DATE_ADDED} > ?)",
            arrayOf("$home/%", since.toString(), since.toString()),
            "${MediaStore.Files.FileColumns.DATE_MODIFIED} DESC"
        )?.use { cursor ->
            while (cursor.moveToNext() && items.size < limit) {
                val raw = cursor.getString(0) ?: continue
                val file = runCatching { File(raw).canonicalFile }.getOrNull() ?: continue
                val path = file.path
                if (!path.startsWith(homeCanonical + File.separator) || !file.isFile) continue
                val relative = path.removePrefix(homeCanonical)
                if (relative.contains("/.") || relative.startsWith("/Android/")) continue
                items += FileItem(
                    name = file.name,
                    path = path,
                    size = cursor.getLong(1),
                    lastModified = cursor.getLong(2) * 1000L,
                    storageType = StorageType.LOCAL,
                    added = cursor.getLong(3) * 1000L
                )
            }
        }
        // Newest event first, whether the file came or changed.
        return items.sortedByDescending { maxOf(it.lastModified, it.added) }
    }

    private fun mediaType(type: Int) = "${MediaStore.Files.FileColumns.MEDIA_TYPE} = ?" to arrayOf(type.toString())

    companion object {
        private val EXTENSIONS = mapOf(
            FileCategory.DOCUMENT to listOf("pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "odt", "ods", "odp", "rtf", "epub", "fb2", "djvu", "txt", "md"),
            FileCategory.ARCHIVE to listOf("zip", "7z", "rar", "tar", "gz", "tgz", "bz2", "xz", "zst", "lz4")
        )
    }
}
