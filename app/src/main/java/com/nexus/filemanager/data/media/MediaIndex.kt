package com.nexus.filemanager.data.media

import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import com.nexus.filemanager.data.model.FileCategory
import com.nexus.filemanager.data.model.FileItem
import com.nexus.filemanager.data.model.StorageType
import java.io.File

/** All files of a kind in internal storage, from Android's media index instead of walking the disk. */
class MediaIndex(private val context: Context) {

    private val home = Environment.getExternalStorageDirectory().absolutePath

    fun query(category: FileCategory): List<FileItem> {
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
                val path = c.getString(0) ?: continue
                // The index may still list files that were deleted a moment ago.
                if (!File(path).isFile) continue
                items += FileItem(
                    name = path.substringAfterLast('/'),
                    path = path,
                    size = c.getLong(1),
                    lastModified = c.getLong(2) * 1000L,
                    storageType = StorageType.LOCAL
                )
            }
        }
        return items
    }

    private fun mediaType(type: Int) = "${MediaStore.Files.FileColumns.MEDIA_TYPE} = ?" to arrayOf(type.toString())

    companion object {
        private val EXTENSIONS = mapOf(
            FileCategory.DOCUMENT to listOf("pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "odt", "ods", "odp", "rtf", "epub", "fb2", "djvu", "txt", "md"),
            FileCategory.ARCHIVE to listOf("zip", "7z", "rar", "tar", "gz", "tgz", "bz2", "xz", "zst", "lz4")
        )
    }
}
