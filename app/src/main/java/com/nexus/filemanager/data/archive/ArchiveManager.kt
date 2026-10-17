package com.nexus.filemanager.data.archive

import com.nexus.filemanager.data.model.FileItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream

object ArchiveManager {

    data class Progress(
        val currentFileName: String,
        val processedCount: Int,
        val totalCount: Int = 0,
        val percentage: Float = 0f
    )

    // Zip Slip: the separator matters, or "/out/x2" would pass as inside "/out/x".
    private fun isInside(file: File, dir: File): Boolean =
        file.canonicalPath.startsWith(dir.canonicalPath + File.separator)

    fun isArchive(name: String): Boolean {
        val lower = name.lowercase()
        return lower.endsWith(".zip") ||
               lower.endsWith(".tar") ||
               lower.endsWith(".tar.gz") ||
               lower.endsWith(".tgz") ||
               lower.endsWith(".tar.bz2") ||
               lower.endsWith(".tbz2") ||
               lower.endsWith(".tar.xz") ||
               lower.endsWith(".txz") ||
               lower.endsWith(".7z") ||
               lower.endsWith(".gz") ||
               lower.endsWith(".bz2") ||
               lower.endsWith(".xz")
    }

    suspend fun extractArchive(
        archiveFile: File,
        targetDirectory: File,
        onProgress: (Progress) -> Unit = {}
    ) = withContext(Dispatchers.IO) {
        if (!targetDirectory.exists()) {
            targetDirectory.mkdirs()
        }

        val name = archiveFile.name.lowercase()
        when {
            name.endsWith(".7z") -> extract7z(archiveFile, targetDirectory, onProgress)
            name.endsWith(".tar.gz") || name.endsWith(".tgz") -> extractTarGz(archiveFile, targetDirectory, onProgress)
            name.endsWith(".tar.bz2") || name.endsWith(".tbz2") -> extractTarBz2(archiveFile, targetDirectory, onProgress)
            name.endsWith(".tar.xz") || name.endsWith(".txz") -> extractTarXz(archiveFile, targetDirectory, onProgress)
            name.endsWith(".tar") -> extractTar(archiveFile, targetDirectory, onProgress)
            name.endsWith(".zip") -> extractZip(archiveFile, targetDirectory, onProgress)
            name.endsWith(".gz") -> extractSingleGz(archiveFile, targetDirectory, onProgress)
            else -> extractZip(archiveFile, targetDirectory, onProgress)
        }
    }

    private fun extractZip(
        archiveFile: File,
        targetDir: File,
        onProgress: (Progress) -> Unit
    ) {
        var count = 0
        ZipArchiveInputStream(BufferedInputStream(FileInputStream(archiveFile))).use { zis ->
            var entry: ZipArchiveEntry?
            while (zis.nextEntry.also { entry = it } != null) {
                val currentEntry = entry ?: continue
                val outputFile = File(targetDir, currentEntry.name)
                // Zip Slip, and links: an archive's link could point the next entries outside the target.
                if (!isInside(outputFile, targetDir) || currentEntry.isUnixSymlink) {
                    continue
                }

                count++
                onProgress(Progress(currentEntry.name, count))

                if (currentEntry.isDirectory) {
                    outputFile.mkdirs()
                } else {
                    outputFile.parentFile?.mkdirs()
                    BufferedOutputStream(FileOutputStream(outputFile)).use { bos ->
                        zis.copyTo(bos)
                    }
                }
            }
        }
    }

    private fun extractTarStream(
        inputStream: InputStream,
        targetDir: File,
        onProgress: (Progress) -> Unit
    ) {
        var count = 0
        TarArchiveInputStream(BufferedInputStream(inputStream)).use { tis ->
            var entry: TarArchiveEntry?
            while (tis.nextEntry.also { entry = it } != null) {
                val currentEntry = entry ?: continue
                val outputFile = File(targetDir, currentEntry.name)
                // Only files and directories: links and device nodes are skipped.
                if (!isInside(outputFile, targetDir) || !(currentEntry.isFile || currentEntry.isDirectory)) {
                    continue
                }

                count++
                onProgress(Progress(currentEntry.name, count))

                if (currentEntry.isDirectory) {
                    outputFile.mkdirs()
                } else {
                    outputFile.parentFile?.mkdirs()
                    BufferedOutputStream(FileOutputStream(outputFile)).use { bos ->
                        tis.copyTo(bos)
                    }
                }
            }
        }
    }

    private fun extractTar(archiveFile: File, targetDir: File, onProgress: (Progress) -> Unit) {
        extractTarStream(FileInputStream(archiveFile), targetDir, onProgress)
    }

    private fun extractTarGz(archiveFile: File, targetDir: File, onProgress: (Progress) -> Unit) {
        extractTarStream(GzipCompressorInputStream(FileInputStream(archiveFile)), targetDir, onProgress)
    }

    private fun extractTarBz2(archiveFile: File, targetDir: File, onProgress: (Progress) -> Unit) {
        extractTarStream(BZip2CompressorInputStream(FileInputStream(archiveFile)), targetDir, onProgress)
    }

    private fun extractTarXz(archiveFile: File, targetDir: File, onProgress: (Progress) -> Unit) {
        extractTarStream(XZCompressorInputStream(FileInputStream(archiveFile)), targetDir, onProgress)
    }

    private fun extract7z(archiveFile: File, targetDir: File, onProgress: (Progress) -> Unit) {
        SevenZFile.builder().setFile(archiveFile).get().use { sevenZFile ->
            var count = 0
            val total = sevenZFile.entries.count()
            var entry = sevenZFile.nextEntry
            while (entry != null) {
                val outputFile = File(targetDir, entry.name)
                if (!isInside(outputFile, targetDir)) {
                    entry = sevenZFile.nextEntry
                    continue
                }

                count++
                onProgress(Progress(entry.name, count, total, if (total > 0) count.toFloat() / total else 0f))

                if (entry.isDirectory) {
                    outputFile.mkdirs()
                } else {
                    outputFile.parentFile?.mkdirs()
                    BufferedOutputStream(FileOutputStream(outputFile)).use { bos ->
                        val buffer = ByteArray(8192)
                        var bytesRead: Int
                        while (sevenZFile.read(buffer).also { bytesRead = it } != -1) {
                            bos.write(buffer, 0, bytesRead)
                        }
                    }
                }
                entry = sevenZFile.nextEntry
            }
        }
    }

    private fun extractSingleGz(archiveFile: File, targetDir: File, onProgress: (Progress) -> Unit) {
        val outName = archiveFile.name.removeSuffix(".gz")
        val outFile = File(targetDir, outName)
        onProgress(Progress(outName, 1, 1, 1f))
        GzipCompressorInputStream(BufferedInputStream(FileInputStream(archiveFile))).use { gzis ->
            BufferedOutputStream(FileOutputStream(outFile)).use { bos ->
                gzis.copyTo(bos)
            }
        }
    }

    // Compression Methods
    suspend fun createZipArchive(
        sourceFiles: List<File>,
        destinationZipFile: File,
        onProgress: (Progress) -> Unit = {}
    ) = withContext(Dispatchers.IO) {
        run {
            destinationZipFile.parentFile?.mkdirs()
            var count = 0
            val allFiles = mutableListOf<Pair<File, String>>()

            for (src in sourceFiles) {
                if (src.isDirectory) {
                    src.walkTopDown().forEach { file ->
                        val relative = src.name + "/" + file.relativeTo(src).path
                        allFiles.add(Pair(file, relative.trimEnd('/')))
                    }
                } else {
                    allFiles.add(Pair(src, src.name))
                }
            }

            ZipArchiveOutputStream(BufferedOutputStream(FileOutputStream(destinationZipFile))).use { zos ->
                for ((file, entryName) in allFiles) {
                    count++
                    onProgress(Progress(file.name, count, allFiles.size, count.toFloat() / allFiles.size))
                    val entry = ZipArchiveEntry(file, if (file.isDirectory) "$entryName/" else entryName)
                    zos.putArchiveEntry(entry)
                    if (file.isFile) {
                        BufferedInputStream(FileInputStream(file)).use { bis ->
                            bis.copyTo(zos)
                        }
                    }
                    zos.closeArchiveEntry()
                }
            }
        }
    }
}
