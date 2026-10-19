package com.yafm.filemanager.data.dupes

import com.yafm.filemanager.data.model.FileItem
import java.io.File
import java.security.MessageDigest

/** Files with the same content, wherever they are and whatever they are called. */
object DuplicateFinder {

    private const val HEAD_BYTES = 64 * 1024

    /**
     * Groups of identical files, the most space wasted first; each group oldest first.
     * Only files of equal size are read: first their start, then in full the ones that still match.
     */
    fun find(files: List<FileItem>, onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }): List<List<FileItem>> {
        val sameSize = files.filter { !it.isDirectory && it.size > 0 }
            .groupBy { it.size }.values.filter { it.size > 1 }
        val total = sameSize.sumOf { it.size }
        var done = 0
        val result = mutableListOf<List<FileItem>>()
        for (group in sameSize) {
            val byHead = group.groupBy { item -> digest(item, HEAD_BYTES.toLong()).also { onProgress(++done, total) } }
            for (candidates in byHead.values) {
                if (candidates.size < 2) continue
                // A file shorter than the head was read whole already.
                val full = if (candidates[0].size <= HEAD_BYTES) mapOf("" to candidates)
                else candidates.groupBy { digest(it, Long.MAX_VALUE) }
                full.values.filter { it.size > 1 }.forEach { same -> result += same.sortedBy { it.lastModified } }
            }
        }
        return result.sortedByDescending { it[0].size * (it.size - 1) }
    }

    /** An unreadable file gets a key of its own, so it never matches another one. */
    private fun digest(item: FileItem, limit: Long): String = runCatching {
        val md = MessageDigest.getInstance("MD5")
        File(item.path).inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            var left = limit
            while (left > 0) {
                val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                if (n < 0) break
                md.update(buf, 0, n)
                left -= n
            }
        }
        md.digest().joinToString("") { "%02x".format(it) }
    }.getOrNull() ?: "?${item.path}"
}
