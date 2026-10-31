package com.yafm.filemanager.data.transfer

import com.yafm.filemanager.R
import com.yafm.filemanager.data.fs.FileSystem
import com.yafm.filemanager.data.fs.PRIVATE_MODE
import com.yafm.filemanager.data.fs.checkName
import com.yafm.filemanager.data.fs.joinPath
import com.yafm.filemanager.data.fs.parentPath
import com.yafm.filemanager.data.fs.uniqueName
import com.yafm.filemanager.data.model.FileItem
import com.yafm.filemanager.str
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream

/** What happens to a name already taken in the target. Folders are merged, by these rules for their files. */
enum class OnConflict { REPLACE, SKIP, KEEP_BOTH, SKIP_SAME }

/** Runs blocking storage calls off the main thread; each may be repeated, so they must be safe to repeat. */
interface Runner {
    suspend fun <T> run(block: () -> T): T
}

/**
 * Copies and moves into [dst]. Each file goes to a hidden partial file that takes the real name only when
 * it is complete, so a replaced file survives a failed copy, and a lost connection or a later retry goes
 * on from where it stopped.
 */
class Copier(
    private val dst: FileSystem,
    private val policy: OnConflict,
    private val meter: TransferMeter,
    private val runner: Runner,
    private val retryDelaysMs: List<Long> = listOf(2_000, 5_000, 15_000, 30_000)
) {
    /** Links to folders left out (a link to `.` would never end). */
    var skippedLinks = 0
        private set

    /** Files written, for Android's media index. */
    val written = mutableListOf<String>()

    /** Puts [item] into [destDir]; [taken]: what is there by name, kept up to date. [size]: its bytes, for the meter. */
    suspend fun place(src: FileSystem, item: FileItem, destDir: String, move: Boolean, taken: MutableMap<String, FileItem>, size: Long) {
        val same = src === dst
        if (move && same && parentPath(item.path) == destDir.trimEnd('/').ifEmpty { "/" }) return meter.skip(size)
        checkName(item.name)
        val existing = taken[item.name]
        val onItself = same && joinPath(destDir, item.name) == item.path
        val name = when {
            existing == null -> item.name
            onItself || policy == OnConflict.KEEP_BOTH -> uniqueName(item.name, taken.keys)
            policy == OnConflict.SKIP -> return meter.skip(size)
            policy == OnConflict.SKIP_SAME && isSame(item, existing) -> {
                // It arrived before the last try stopped.
                if (move && !same) runner.run { src.delete(item) }
                return meter.skip(size)
            }
            existing.isDirectory != item.isDirectory -> throw IOException(str(R.string.already_exists, item.name))
            else -> item.name
        }
        val target = joinPath(destDir, name)
        if (same && item.isDirectory && (target + "/").startsWith(item.path.trimEnd('/') + "/")) {
            throw IOException(str(R.string.folder_cannot_be_copied_into_itself, item.name))
        }
        val clash = name in taken
        taken[name] = item
        if (move && same && (!clash || !item.isDirectory)) {
            runner.run { if (clash) dst.replace(item.path, target) else dst.rename(item.path, target) }
            return meter.skip(size)
        }
        if (!move && same && !clash && runner.run { src.copyWithin(item, target) }) return meter.skip(size)
        // Only what arrived whole may go from the source.
        if (copy(src, item, target, clash) && move) runner.run { src.delete(item) }
    }

    private fun isSame(item: FileItem, existing: FileItem) =
        !item.isDirectory && !existing.isDirectory && item.size == existing.size

    private suspend fun copy(src: FileSystem, item: FileItem, target: String, exists: Boolean): Boolean {
        if (item.isDirectory) return copyFolder(src, item, target, exists)
        copyFile(src, item, target, exists)
        return true
    }

    private suspend fun copyFolder(src: FileSystem, item: FileItem, target: String, exists: Boolean): Boolean {
        if (item.isSymlink) {
            skippedLinks++
            return false
        }
        if (!exists) attempt(src) { dst.mkdir(target) }
        val inTarget = if (exists) attempt(src) { dst.list(target) }.associateBy { it.name } else emptyMap()
        var whole = true
        for (child in attempt(src) { src.list(item.path) }) {
            checkName(child.name)
            val there = inTarget[child.name]
            val skip = there != null && when (policy) {
                OnConflict.SKIP -> true
                OnConflict.SKIP_SAME -> isSame(child, there)
                else -> false
            }
            if (skip) {
                // A folder is gone through, as it may hold what a retry still has to bring.
                if (child.isDirectory) whole = copyFolder(src, child, joinPath(target, child.name), true) && whole
                else { meter.skip(child.size); whole = whole && policy == OnConflict.SKIP_SAME }
                continue
            }
            if (there != null && there.isDirectory != child.isDirectory) throw IOException(str(R.string.already_exists, child.name))
            whole = copy(src, child, joinPath(target, child.name), there != null) && whole
        }
        return whole
    }

    private suspend fun copyFile(src: FileSystem, item: FileItem, target: String, exists: Boolean) {
        val part = joinPath(parentPath(target), ".${item.name.take(MAX_PART_NAME)}.yafm-part")
        meter.startFile(item.name)
        try {
            attempt(src) { send(src, item, part, exists) }
        } catch (e: CancellationException) {
            withContext(NonCancellable) { runCatching { runner.run { dst.removeFile(part) } } }
            throw e
        }
        attempt(src) {
            if (exists) dst.replaceKeeping(part, target) else dst.rename(part, target)
            if (item.lastModified > 0) runCatching { dst.setModified(target, item.lastModified) }
        }
        written += target
        meter.finishFile()
    }

    /** Repeats [block] while the connection is what fails; storages reconnect by themselves. */
    private suspend fun <T> attempt(src: FileSystem, block: () -> T): T {
        var tries = 0
        while (true) {
            try {
                return runner.run(block).also { meter.reconnecting(false) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (tries >= retryDelaysMs.size || !connectionLost(src, e)) throw e
                meter.reconnecting(true)
                delay(retryDelaysMs[tries++])
            }
        }
    }

    private fun connectionLost(src: FileSystem, e: Exception) =
        e is java.net.SocketException || e is java.net.SocketTimeoutException || e is java.io.EOFException ||
            listOf(src, dst).any { it.isRemote && !it.isOpen }

    private fun send(src: FileSystem, item: FileItem, part: String, exists: Boolean) {
        val offset = resumeOffset(src, item, part)
        meter.fileAt(offset)
        var copied = 0L
        src.openRead(item.path, offset).use { input ->
            val output = if (offset > 0) dst.openAppend(part) else dst.openWrite(part)
            output!!.use { out ->
                if (exists) runCatching { dst.chmod(part, PRIVATE_MODE) }
                val buffer = ByteArray(BUFFER)
                while (true) {
                    if (Thread.currentThread().isInterrupted) throw InterruptedException()
                    val n = input.read(buffer)
                    if (n < 0) break
                    out.write(buffer, 0, n)
                    copied += n
                    meter.add(n)
                }
            }
        }
        val written = dst.stat(part)?.size
        if (written != null && written != offset + copied) throw IOException(str(R.string.file_arrived_incomplete, item.name))
    }

    /** How much of a partial file left earlier can stay: it must be the start of this very file. */
    private fun resumeOffset(src: FileSystem, item: FileItem, part: String): Long {
        val have = dst.stat(part)?.size ?: return 0L
        if (have <= 0 || have > item.size || !dst.canAppend) return 0L
        val tail = minOf(have, CHECKED_TAIL.toLong())
        val mine = src.openRead(item.path, have - tail).use { it.readExactly(tail.toInt()) }
        val theirs = dst.openRead(part, have - tail).use { it.readExactly(tail.toInt()) }
        return if (mine.contentEquals(theirs)) have else 0L
    }

    private fun InputStream.readExactly(size: Int): ByteArray {
        val bytes = ByteArray(size)
        var got = 0
        while (got < size) {
            val n = read(bytes, got, size - got)
            if (n < 0) return bytes.copyOf(got)
            got += n
        }
        return bytes
    }

    private companion object {
        const val BUFFER = 256 * 1024
        const val CHECKED_TAIL = 64 * 1024
        // File names are limited to 255 bytes, and a name may be in a multi-byte alphabet.
        const val MAX_PART_NAME = 100
    }
}
