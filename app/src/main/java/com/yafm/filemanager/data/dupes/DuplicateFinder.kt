package com.yafm.filemanager.data.dupes

import com.yafm.filemanager.data.model.FileItem
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Executors

/** Files with the same content, wherever they are and whatever they are called. */
object DuplicateFinder {

    private const val HEAD_BYTES = 64 * 1024
    private const val THREADS = 4

    /**
     * Groups of identical files, the most space wasted first; each group oldest first.
     * Only files of equal size are read: first their start, then in full the ones that still match.
     * Files are read on a few threads, since flash storage serves parallel reads faster; [onProgress]
     * is called on the calling thread, so it may cancel the search by throwing.
     */
    fun find(files: List<FileItem>, onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }): List<List<FileItem>> {
        val sameSize = files.filter { !it.isDirectory && it.size > 0 }
            .groupBy { it.size }.values.filter { it.size > 1 }
        val total = sameSize.sumOf { it.size }
        var done = 0
        val result = mutableListOf<List<FileItem>>()
        val pool = Executors.newFixedThreadPool(THREADS)
        try {
            val heads = sameSize.map { group -> group.map { item -> pool.submit<String> { digest(item, HEAD_BYTES.toLong()) } } }
            // Files whose start matches and that are longer than it: they are read in full.
            val unsure = mutableListOf<List<FileItem>>()
            sameSize.forEachIndexed { g, group ->
                val byHead = group.indices.groupBy({ heads[g][it].get().also { onProgress(++done, total) } }, { group[it] })
                for (candidates in byHead.values) {
                    if (candidates.size < 2) continue
                    // A file shorter than the head was read whole already.
                    if (candidates[0].size <= HEAD_BYTES) result += candidates.sortedBy { it.lastModified } else unsure += candidates
                }
            }
            val full = unsure.map { group -> group.map { item -> pool.submit<String> { digest(item, Long.MAX_VALUE) } } }
            unsure.forEachIndexed { g, group ->
                val byContent = group.indices.groupBy({ full[g][it].get().also { onProgress(done, total) } }, { group[it] })
                byContent.values.filter { it.size > 1 }.forEach { same -> result += same.sortedBy { it.lastModified } }
            }
        } finally {
            pool.shutdownNow()
        }
        return result.sortedByDescending { it[0].size * (it.size - 1) }
    }

    // Folders that apps fill and clean up themselves: a copy kept there may vanish later.
    private val TEMPORARY = listOf(
        "/android/data/", "/android/media/", "/telegram", "/whatsapp", "/viber", "/cache", "/.thumbnails", "/tmp", "/temp"
    )
    private val LASTING = listOf("/dcim/", "/pictures/", "/music/", "/documents/", "/download/", "/movies/")

    /** The copy to keep: one in a lasting folder over one in a temporary folder, then the oldest. */
    fun keeper(group: List<FileItem>): FileItem = group.minWith(
        compareBy<FileItem> { item ->
            val path = item.path.lowercase()
            when {
                TEMPORARY.any { it in path } -> 2
                LASTING.any { it in path } -> 0
                else -> 1
            }
        }.thenBy { it.lastModified }
    )

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
        hex(md.digest())
    }.getOrNull() ?: "?${item.path}"

    private fun hex(bytes: ByteArray): String {
        val digits = "0123456789abcdef"
        val out = CharArray(bytes.size * 2)
        bytes.forEachIndexed { i, b ->
            out[i * 2] = digits[(b.toInt() shr 4) and 0xf]
            out[i * 2 + 1] = digits[b.toInt() and 0xf]
        }
        return String(out)
    }
}
