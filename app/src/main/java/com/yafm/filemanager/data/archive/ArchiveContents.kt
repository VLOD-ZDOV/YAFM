package com.yafm.filemanager.data.archive

import com.yafm.filemanager.R
import com.yafm.filemanager.str
import org.apache.commons.compress.archivers.ArchiveStreamFactory
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.archivers.zip.ZipFile
import org.apache.commons.compress.compressors.CompressorStreamFactory
import org.apache.commons.compress.compressors.brotli.BrotliCompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream

/** What is inside an archive, and single files out of it, without extracting the rest. */
object ArchiveContents {

    /** A file or folder in the archive; [path] uses "/" and has no leading or trailing slash. */
    data class Entry(val path: String, val size: Long, val isDirectory: Boolean, val modified: Long) {
        val name get() = path.substringAfterLast('/')
    }

    fun list(archive: File): List<Entry> {
        val entries = mutableListOf<Entry>()
        visit(archive) { entry, _ -> entries += entry; true }
        return entries
    }

    /**
     * Copies one file of the archive into [dir] under its own name and returns it. The name is only
     * the last part of the entry's path, so nothing lands outside [dir].
     */
    fun extractOne(archive: File, path: String, dir: File): File {
        dir.mkdirs()
        val name = path.substringAfterLast('/').takeIf { it.isNotEmpty() && it != "." && it != ".." }
            ?: throw IOException(str(R.string.invalid_name, path))
        val out = File(dir, name)
        var found = false
        visit(archive) { entry, input ->
            if (entry.path == path && !entry.isDirectory && input != null) {
                out.outputStream().use { input.copyTo(it, 64 * 1024) }
                found = true
                false
            } else true
        }
        if (!found) throw IOException(str(R.string.not_found_in_archive, path))
        return out
    }

    private fun clean(name: String) = name.replace('\\', '/').trim('/').split('/')
        .filter { it.isNotEmpty() && it != "." }.joinToString("/")

    /** Calls [block] for each entry with its data (null for folders); [block] returns false to stop. */
    private fun visit(archive: File, block: (Entry, InputStream?) -> Boolean) {
        val magic = ByteArray(6)
        val read = FileInputStream(archive).use { it.read(magic) }
        fun starts(sig: ByteArray) = read >= sig.size && sig.indices.all { magic[it] == sig[it] }
        when {
            starts(ArchiveManager.SEVEN_Z_MAGIC) -> visit7z(archive, block)
            starts(ArchiveManager.ZIP_MAGIC) -> visitZip(archive, block)
            else -> runCatching { visitStream(archive, block) }.getOrElse { e ->
                // A self-extracting program: the archive after it.
                if (runCatching { visitZip(archive, block) }.isSuccess) return
                val offset = ArchiveManager.findSignature(archive, ArchiveManager.SEVEN_Z_MAGIC) ?: throw e
                val part = File.createTempFile("embedded", ".7z")
                try {
                    FileInputStream(archive).use { input ->
                        var left = offset
                        while (left > 0) { val s = input.skip(left); if (s <= 0) break; left -= s }
                        part.outputStream().use { input.copyTo(it, 64 * 1024) }
                    }
                    visit7z(part, block)
                } finally {
                    part.delete()
                }
            }
        }
    }

    private fun visitZip(archive: File, block: (Entry, InputStream?) -> Boolean) {
        ZipFile.builder().setFile(archive).get().use { zip ->
            for (e in zip.entries) {
                if (e.isUnixSymlink) continue
                val entry = Entry(clean(e.name), e.size, e.isDirectory, e.time)
                val go = if (e.isDirectory) block(entry, null) else zip.getInputStream(e).use { block(entry, it) }
                if (!go) return
            }
        }
    }

    private fun visit7z(archive: File, block: (Entry, InputStream?) -> Boolean) {
        SevenZFile.builder().setFile(archive).get().use { seven ->
            while (true) {
                val e = seven.nextEntry ?: break
                if (e.isAntiItem) continue
                val entry = Entry(clean(e.name), e.size, e.isDirectory, if (e.hasLastModifiedDate) e.lastModifiedDate.time else 0L)
                val data = if (e.isDirectory) null else object : InputStream() {
                    override fun read(): Int = seven.read()
                    override fun read(b: ByteArray, off: Int, len: Int): Int = seven.read(b, off, len)
                }
                if (!block(entry, data)) return
            }
        }
    }

    private fun visitStream(archive: File, block: (Entry, InputStream?) -> Boolean) {
        var input: InputStream = BufferedInputStream(FileInputStream(archive), 64 * 1024)
        try {
            val compression = runCatching { CompressorStreamFactory.detect(input) }.getOrNull()
            val compressed = when {
                compression != null -> {
                    input = BufferedInputStream(CompressorStreamFactory().createCompressorInputStream(compression, input), 64 * 1024)
                    true
                }
                archive.name.lowercase().endsWith(".br") -> {
                    input = BufferedInputStream(BrotliCompressorInputStream(input), 64 * 1024)
                    true
                }
                else -> false
            }
            val archiver = runCatching { ArchiveStreamFactory.detect(input) }.getOrNull()
            when {
                archiver != null -> {
                    val stream = ArchiveStreamFactory().createArchiveInputStream<org.apache.commons.compress.archivers.ArchiveInputStream<*>>(archiver, input)
                    while (true) {
                        val e = stream.nextEntry ?: break
                        if (!stream.canReadEntryData(e)) continue
                        val entry = Entry(clean(e.name), e.size, e.isDirectory, runCatching { e.lastModifiedDate.time }.getOrDefault(0L))
                        if (!block(entry, if (e.isDirectory) null else stream)) return
                    }
                }
                compressed -> {
                    // One compressed file: its name without the compression suffix.
                    val name = archive.name.substringBeforeLast('.').ifEmpty { "extracted" }
                    block(Entry(name, -1, false, archive.lastModified()), input)
                }
                else -> throw IOException(str(R.string.unknown_archive_format))
            }
        } finally {
            input.close()
        }
    }

    /** The folders and files right inside [folder] ("" for the top), folders first; implied folders included. */
    fun children(entries: List<Entry>, folder: String): List<Entry> {
        val prefix = if (folder.isEmpty()) "" else "$folder/"
        val result = linkedMapOf<String, Entry>()
        for (e in entries) {
            if (!e.path.startsWith(prefix) || e.path == folder) continue
            val rest = e.path.removePrefix(prefix)
            val first = rest.substringBefore('/')
            if (first.isEmpty()) continue
            if ('/' in rest || e.isDirectory) {
                result.putIfAbsent(first, Entry(prefix + first, 0, true, e.modified))
            } else {
                result[first] = e
            }
        }
        return result.values.sortedWith(compareBy<Entry> { !it.isDirectory }.thenBy { it.name.lowercase() })
    }
}
