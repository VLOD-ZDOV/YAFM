package com.nexus.filemanager.data.model

import android.webkit.MimeTypeMap
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class FileCategory {
    FOLDER,
    IMAGE,
    VIDEO,
    AUDIO,
    DOCUMENT,
    ARCHIVE,
    APK,
    CODE,
    TEXT,
    OTHER
}

data class FileItem(
    val name: String,
    val path: String,
    val size: Long = 0L,
    val isDirectory: Boolean = false,
    val lastModified: Long = 0L,
    val permissions: String = if (isDirectory) "rwxr-xr-x" else "rw-r--r--",
    val owner: String = "",
    val group: String = "",
    val storageType: StorageType = StorageType.LOCAL,
    val serverId: String? = null,
    /** A symbolic link; `isDirectory` then tells what it points to. Recursive operations never follow it. */
    val isSymlink: Boolean = false
) {
    val extension: String
        get() = if (isDirectory) "" else name.substringAfterLast('.', "").lowercase()

    val mimeType: String
        get() = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "*/*"

    val isHidden: Boolean
        get() = name.startsWith(".")

    val category: FileCategory
        get() {
            if (isDirectory) return FileCategory.FOLDER
            return when (extension) {
                "jpg", "jpeg", "png", "gif", "webp", "bmp", "svg", "heic", "avif" -> FileCategory.IMAGE
                "mp4", "mkv", "avi", "mov", "webm", "flv", "wmv", "3gp", "ts" -> FileCategory.VIDEO
                "mp3", "flac", "wav", "ogg", "m4a", "aac", "opus", "wma" -> FileCategory.AUDIO
                "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "odt", "ods", "odp" -> FileCategory.DOCUMENT
                "zip", "rar", "7z", "tar", "gz", "bz2", "xz", "tgz" -> FileCategory.ARCHIVE
                "apk", "xapk", "apks" -> FileCategory.APK
                "kt", "java", "py", "c", "cpp", "h", "rs", "go", "js", "ts", "html", "css", "json", "xml", "yaml", "yml", "sh", "prop", "rc", "conf", "toml" -> FileCategory.CODE
                "txt", "md", "log", "cfg", "ini" -> FileCategory.TEXT
                else -> FileCategory.OTHER
            }
        }

    fun formattedSize(): String {
        if (isDirectory) return ""
        if (size <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        var digitGroups = (Math.log10(size.toDouble()) / Math.log10(1024.0)).toInt()
        if (digitGroups >= units.size) digitGroups = units.size - 1
        return String.format(Locale.US, "%.1f %s", size / Math.pow(1024.0, digitGroups.toDouble()), units[digitGroups])
    }

    fun formattedDate(): String {
        if (lastModified <= 0) return ""
        val formatter = SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault())
        return formatter.format(Date(lastModified))
    }
}
