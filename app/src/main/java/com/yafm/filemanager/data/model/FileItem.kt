package com.yafm.filemanager.data.model

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
    OTHER,
    /** Not a kind of file: the files added or changed lately, as a category screen. */
    RECENT
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
    val isSymlink: Boolean = false,
    /** When the media index first saw the file (ms); 0 if unknown. */
    val added: Long = 0L
) {
    /** Added and not changed since (a download, a new photo), as opposed to changed later. */
    val isNew: Boolean
        get() = added > 0 && lastModified - added < 60_000

    // Worked out once: lists read these for every row on every redraw, and when sorting and filtering.
    val extension: String = if (isDirectory) "" else name.substringAfterLast('.', "").lowercase()

    val mimeType: String
        get() = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "*/*"

    val isHidden: Boolean
        get() = name.startsWith(".")

    val category: FileCategory = if (isDirectory) FileCategory.FOLDER else when (extension) {
        "jpg", "jpeg", "png", "gif", "webp", "bmp", "svg", "heic", "avif" -> FileCategory.IMAGE
        "mp4", "mkv", "avi", "mov", "webm", "flv", "wmv", "3gp", "ts" -> FileCategory.VIDEO
        "mp3", "flac", "wav", "ogg", "m4a", "aac", "opus", "wma" -> FileCategory.AUDIO
        "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "odt", "ods", "odp" -> FileCategory.DOCUMENT
        "zip", "rar", "7z", "tar", "gz", "bz2", "xz", "tgz", "tbz2", "txz", "zst", "tzst", "lz4", "tlz4", "lzma", "br", "cpio" -> FileCategory.ARCHIVE
        "apk", "xapk", "apks" -> FileCategory.APK
        "kt", "kts", "java", "py", "c", "cpp", "cc", "h", "hpp", "cs", "rs", "go", "js", "mjs", "tsx", "jsx", "html", "htm",
        "css", "scss", "json", "jsonc", "xml", "yaml", "yml", "sh", "bash", "zsh", "fish", "bat", "cmd", "ps1", "prop",
        "rc", "conf", "toml", "gradle", "lua", "rb", "php", "pl", "sql", "swift", "dart", "vue", "smali", "mk", "cmake",
        "patch", "diff", "reg", "desktop", "service", "plist", "lst" -> FileCategory.CODE
        "txt", "md", "log", "cfg", "ini", "inf", "properties", "env", "csv", "tsv", "srt", "ass", "ssa", "vtt", "sub", "lrc",
        "nfo", "rst", "tex", "org", "cue", "m3u", "m3u8", "pls", "gitignore", "gitattributes", "editorconfig" -> FileCategory.TEXT
        else -> FileCategory.OTHER
    }

    /** A short name of the file's kind for its icon: "Word" says more than a blue page. */
    val typeLabel: String
        get() = when {
            isDirectory -> ""
            TAR_SUFFIXES.any { name.endsWith(it, ignoreCase = true) } ->
                name.lowercase().substringAfter(".tar.").let { "TAR.${it.uppercase()}" }
            else -> when (extension) {
                "doc", "docx", "odt", "rtf" -> "Word"
                "xls", "xlsx", "ods", "csv" -> "Excel"
                "ppt", "pptx", "odp" -> "PPT"
                "pdf" -> "PDF"
                "apk", "xapk", "apks" -> "APK"
                else -> extension.uppercase().take(5)
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
        // One formatter per thread and language instead of a new one for every row.
        val locale = Locale.getDefault()
        val formatter = DATE_FORMAT.get()?.takeIf { it.first == locale }?.second
            ?: SimpleDateFormat("dd MMM yyyy, HH:mm", locale).also { DATE_FORMAT.set(locale to it) }
        return formatter.format(Date(lastModified))
    }

    private companion object {
        val TAR_SUFFIXES = listOf(".tar.gz", ".tar.xz", ".tar.bz2", ".tar.zst", ".tar.lz4")
        val DATE_FORMAT = ThreadLocal<Pair<Locale, SimpleDateFormat>>()
    }
}
