package com.yafm.filemanager.data.archive

import com.yafm.filemanager.data.fs.shellQuote

/** Shell commands that make and unpack archives on a server, with its own programs and without the phone in between. */
object RemoteArchive {

    /** Packs [names], all in [dir], into [archive] there. */
    fun compress(format: ArchiveManager.Format, dir: String, names: List<String>, archive: String): String {
        val files = names.joinToString(" ") { shellQuote("./$it") }
        val out = shellQuote(archive)
        val tar = when (format) {
            ArchiveManager.Format.TAR -> "tar -cf"
            ArchiveManager.Format.TAR_GZ -> "tar -czf"
            ArchiveManager.Format.TAR_BZ2 -> "tar -cjf"
            ArchiveManager.Format.TAR_XZ -> "tar -cJf"
            ArchiveManager.Format.TAR_ZST -> "tar --zstd -cf"
            ArchiveManager.Format.TAR_LZ4 -> "tar -I lz4 -cf"
            ArchiveManager.Format.ZIP -> "zip -q -r"
            ArchiveManager.Format.SEVEN_Z -> "7z a -bd"
        }
        return "cd ${shellQuote(dir)} && $tar $out $files"
    }

    /** Null: not a kind the server's usual programs unpack as a folder. */
    fun extract(archive: String, target: String): String? {
        val lower = archive.lowercase()
        val from = shellQuote(archive)
        val into = shellQuote(target)
        val unpack = when {
            TAR.any { lower.endsWith(it) } -> "tar -xf $from -C $into"
            lower.endsWith(".zip") -> "unzip -q $from -d $into"
            lower.endsWith(".7z") -> "7z x -bd -o$into $from"
            else -> return null
        }
        return "mkdir -p $into && $unpack"
    }

    /** The archive's name without its suffix. */
    fun folderName(archive: String): String {
        val lower = archive.lowercase()
        val suffix = (TAR + ".zip" + ".7z").firstOrNull { lower.endsWith(it) } ?: return archive
        return archive.dropLast(suffix.length).ifEmpty { archive }
    }

    private val TAR = listOf(".tar.gz", ".tar.bz2", ".tar.xz", ".tar.zst", ".tar.lz4", ".tgz", ".tbz2", ".tbz", ".txz", ".tzst", ".tar")
}
