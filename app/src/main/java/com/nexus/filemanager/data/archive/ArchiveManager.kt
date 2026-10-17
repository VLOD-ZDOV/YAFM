package com.nexus.filemanager.data.archive

import com.nexus.filemanager.str
import com.nexus.filemanager.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.ArchiveEntry
import org.apache.commons.compress.archivers.ArchiveInputStream
import org.apache.commons.compress.archivers.ArchiveOutputStream
import org.apache.commons.compress.archivers.ArchiveStreamFactory
import org.apache.commons.compress.archivers.ar.ArArchiveEntry
import org.apache.commons.compress.archivers.cpio.CpioArchiveEntry
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import org.apache.commons.compress.archivers.zip.ZipFile
import org.apache.commons.compress.compressors.CompressorStreamFactory
import org.apache.commons.compress.compressors.brotli.BrotliCompressorInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import org.apache.commons.compress.compressors.lz4.FramedLZ4CompressorOutputStream
import org.apache.commons.compress.compressors.xz.XZCompressorOutputStream
import org.apache.commons.compress.compressors.zstandard.ZstdCompressorOutputStream
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files

/**
 * Archives by content, not by name: the format is detected from the file's first bytes, so a
 * tar.lz4 or a renamed archive opens as what it is.
 */
object ArchiveManager {

    data class Progress(
        val currentFileName: String,
        val processedCount: Int,
        val totalCount: Int = 0,
        val percentage: Float = 0f
    )

    enum class Format(val extension: String, val label: String) {
        ZIP(".zip", "ZIP"),
        SEVEN_Z(".7z", "7z"),
        TAR(".tar", "TAR"),
        TAR_GZ(".tar.gz", "TAR.GZ"),
        TAR_BZ2(".tar.bz2", "TAR.BZ2"),
        TAR_XZ(".tar.xz", "TAR.XZ"),
        TAR_ZST(".tar.zst", "TAR.ZST"),
        TAR_LZ4(".tar.lz4", "TAR.LZ4")
    }

    private val ARCHIVE_SUFFIXES = listOf(
        ".zip", ".7z", ".tar", ".tgz", ".tbz", ".tbz2", ".txz", ".tzst", ".tlz4", ".ar", ".cpio",
        ".gz", ".bz2", ".xz", ".lzma", ".zst", ".zstd", ".lz4", ".sz", ".br", ".z"
    )

    /** Whether opening the file should offer extraction (by name; extraction itself goes by content). */
    fun isArchive(name: String): Boolean {
        val lower = name.lowercase()
        return ARCHIVE_SUFFIXES.any { lower.endsWith(it) }
    }

    // Zip Slip, and links already in the target: the canonical path resolves them, so nothing lands outside.
    private fun isInside(file: File, dir: File): Boolean =
        file.canonicalPath.startsWith(dir.canonicalPath + File.separator)

    suspend fun extractArchive(archiveFile: File, targetDirectory: File, onProgress: (Progress) -> Unit = {}) =
        withContext(Dispatchers.IO) {
            targetDirectory.mkdirs()
            val magic = ByteArray(6)
            val read = FileInputStream(archiveFile).use { it.read(magic) }
            when {
                read >= 6 && magic.startsWith(SEVEN_Z_MAGIC) -> extract7z(archiveFile, targetDirectory, onProgress)
                read >= 4 && magic.startsWith(ZIP_MAGIC) -> extractZip(archiveFile, targetDirectory, onProgress)
                else -> extractStream(archiveFile, targetDirectory, onProgress)
            }
        }

    private fun ByteArray.startsWith(prefix: ByteArray) = prefix.indices.all { this[it] == prefix[it] }

    private fun extractZip(archive: File, target: File, onProgress: (Progress) -> Unit) {
        ZipFile.builder().setFile(archive).get().use { zip ->
            val entries = zip.entries.toList()
            entries.forEachIndexed { i, entry ->
                onProgress(Progress(entry.name, i + 1, entries.size, (i + 1f) / entries.size))
                if (entry.isUnixSymlink) return@forEachIndexed
                val out = safeTarget(target, entry.name) ?: return@forEachIndexed
                if (entry.isDirectory) out.mkdirs() else zip.getInputStream(entry).use { writeFile(it, out) }
            }
        }
    }

    private fun extract7z(archive: File, target: File, onProgress: (Progress) -> Unit) {
        SevenZFile.builder().setFile(archive).get().use { seven ->
            val total = seven.entries.count()
            var count = 0
            while (true) {
                val entry = seven.nextEntry ?: break
                count++
                onProgress(Progress(entry.name, count, total, if (total > 0) count.toFloat() / total else 0f))
                if (entry.isAntiItem) continue
                val out = safeTarget(target, entry.name) ?: continue
                if (entry.isDirectory) {
                    out.mkdirs()
                } else {
                    out.parentFile?.mkdirs()
                    if (Files.isSymbolicLink(out.toPath())) continue
                    BufferedOutputStream(FileOutputStream(out), 64 * 1024).use { bos ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val n = seven.read(buffer)
                            if (n < 0) break
                            bos.write(buffer, 0, n)
                        }
                    }
                }
            }
        }
    }

    /** Any detected compression around a tar/ar/cpio stream, or around a single file. */
    private fun extractStream(archive: File, target: File, onProgress: (Progress) -> Unit) {
        val lower = archive.name.lowercase()
        var input: InputStream = BufferedInputStream(FileInputStream(archive), 64 * 1024)
        try {
            val compression = runCatching { CompressorStreamFactory.detect(input) }.getOrNull()
            val compressed = when {
                compression != null -> {
                    input = BufferedInputStream(CompressorStreamFactory().createCompressorInputStream(compression, input), 64 * 1024)
                    true
                }
                // Brotli has no signature to detect it by.
                lower.endsWith(".br") -> {
                    input = BufferedInputStream(BrotliCompressorInputStream(input), 64 * 1024)
                    true
                }
                else -> false
            }
            val archiver = runCatching { ArchiveStreamFactory.detect(input) }.getOrNull()
            when {
                archiver != null -> {
                    val stream: ArchiveInputStream<*> = ArchiveStreamFactory().createArchiveInputStream(archiver, input)
                    var count = 0
                    while (true) {
                        val entry: ArchiveEntry = stream.nextEntry ?: break
                        count++
                        onProgress(Progress(entry.name, count))
                        if (!isPlain(entry) || !stream.canReadEntryData(entry)) continue
                        val out = safeTarget(target, entry.name) ?: continue
                        if (entry.isDirectory) out.mkdirs() else writeFile(stream, out)
                    }
                }
                compressed -> {
                    // A single compressed file: its name without the compression suffix.
                    val name = archive.name.substringBeforeLast('.').ifEmpty { "extracted" }
                    val out = safeTarget(target, name) ?: throw IOException(str(R.string.invalid_name, name))
                    onProgress(Progress(name, 1, 1, 1f))
                    writeFile(input, out)
                }
                else -> throw IOException(str(R.string.unknown_archive_format))
            }
        } finally {
            input.close()
        }
    }

    /** Only regular files and directories: links and device nodes are skipped. */
    private fun isPlain(entry: ArchiveEntry): Boolean = when (entry) {
        // TarArchiveEntry.isFile() is also true for links and devices: rule them out by type.
        is TarArchiveEntry -> entry.isDirectory || !(entry.isSymbolicLink || entry.isLink || entry.isCharacterDevice ||
            entry.isBlockDevice || entry.isFIFO || entry.isGlobalPaxHeader || entry.isPaxHeader)
        is CpioArchiveEntry -> entry.isRegularFile || entry.isDirectory
        is ZipArchiveEntry -> !entry.isUnixSymlink
        is ArArchiveEntry -> true
        else -> true
    }

    private fun safeTarget(target: File, name: String): File? = File(target, name).takeIf { isInside(it, target) }

    private fun writeFile(input: InputStream, out: File) {
        out.parentFile?.mkdirs()
        // An existing link at the target would be written through.
        if (Files.isSymbolicLink(out.toPath())) return
        BufferedOutputStream(FileOutputStream(out), 64 * 1024).use { input.copyTo(it, 64 * 1024) }
    }

    suspend fun createArchive(
        sources: List<File>,
        destination: File,
        format: Format,
        onProgress: (Progress) -> Unit = {}
    ) = withContext(Dispatchers.IO) {
        destination.parentFile?.mkdirs()
        // Links are skipped: following them could pack files from anywhere, or loop forever.
        val all = mutableListOf<Pair<File, String>>()
        fun walk(file: File, name: String) {
            if (Files.isSymbolicLink(file.toPath())) return
            all += file to name
            if (file.isDirectory) file.listFiles()?.sortedBy { it.name }?.forEach { walk(it, "$name/${it.name}") }
        }
        sources.forEach { walk(it, it.name) }
        if (all.any { it.first.canonicalPath == destination.canonicalPath }) throw IOException(str(R.string.an_archive_cannot_contain_itself))

        try {
            if (format == Format.SEVEN_Z) {
                SevenZOutputFile(destination).use { seven ->
                    all.forEachIndexed { i, (file, name) ->
                        onProgress(Progress(file.name, i + 1, all.size, (i + 1f) / all.size))
                        seven.putArchiveEntry(seven.createArchiveEntry(file, name))
                        if (file.isFile) FileInputStream(file).use { input ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                val n = input.read(buffer)
                                if (n < 0) break
                                seven.write(buffer, 0, n)
                            }
                        }
                        seven.closeArchiveEntry()
                    }
                }
            } else {
                val fileOut = BufferedOutputStream(FileOutputStream(destination), 64 * 1024)
                @Suppress("UNCHECKED_CAST")
                val out = when (format) {
                    Format.ZIP -> ZipArchiveOutputStream(fileOut)
                    else -> TarArchiveOutputStream(compressor(format, fileOut)).apply {
                        setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
                        setBigNumberMode(TarArchiveOutputStream.BIGNUMBER_POSIX)
                    }
                } as ArchiveOutputStream<ArchiveEntry>
                out.use {
                    all.forEachIndexed { i, (file, name) ->
                        onProgress(Progress(file.name, i + 1, all.size, (i + 1f) / all.size))
                        out.putArchiveEntry(out.createArchiveEntry(file, if (file.isDirectory) "$name/" else name))
                        if (file.isFile) FileInputStream(file).use { it.copyTo(out, 64 * 1024) }
                        out.closeArchiveEntry()
                    }
                    out.finish()
                }
            }
        } catch (e: Exception) {
            destination.delete()
            throw e
        }
    }

    private fun compressor(format: Format, out: OutputStream): OutputStream = when (format) {
        Format.TAR -> out
        Format.TAR_GZ -> GzipCompressorOutputStream(out)
        Format.TAR_BZ2 -> BZip2CompressorOutputStream(out)
        Format.TAR_XZ -> XZCompressorOutputStream(out)
        Format.TAR_ZST -> ZstdCompressorOutputStream(out)
        Format.TAR_LZ4 -> FramedLZ4CompressorOutputStream(out)
        Format.ZIP, Format.SEVEN_Z -> error("not a tar format")
    }

    private val SEVEN_Z_MAGIC = byteArrayOf(0x37, 0x7A, 0xBC.toByte(), 0xAF.toByte(), 0x27, 0x1C)
    private val ZIP_MAGIC = byteArrayOf(0x50, 0x4B, 0x03, 0x04)
}
