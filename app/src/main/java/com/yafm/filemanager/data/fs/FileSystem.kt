package com.yafm.filemanager.data.fs

import com.yafm.filemanager.str
import com.yafm.filemanager.R
import com.yafm.filemanager.data.model.FileItem
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** What a path itself is, a link not followed. [mode], [uid], [gid]: where the storage has them. */
data class FileStat(
    val size: Long,
    val isDirectory: Boolean,
    val mode: Int? = null,
    val uid: Int? = null,
    val gid: Int? = null,
    val isLink: Boolean = false
)

/** Only the owner can read a partial file: it may become a private one. */
const val PRIVATE_MODE = 0b110000000

/** A command run on a server. */
data class ExecResult(val code: Int, val output: String, val errors: String)

/**
 * One storage backend. Calls block and throw on failure; [com.yafm.filemanager.data.repository.FileRepository]
 * runs them on the IO dispatcher and turns errors into messages.
 */
interface FileSystem : Closeable {
    fun list(dir: String): List<FileItem>
    fun openRead(path: String): InputStream
    fun openWrite(path: String): OutputStream
    fun mkdir(path: String)
    fun rename(from: String, to: String)
    fun removeFile(path: String)
    fun removeDir(path: String)

    /** From [offset]; backends that can start there without reading the rest override this. */
    fun openRead(path: String, offset: Long): InputStream {
        val input = openRead(path)
        var left = offset
        while (left > 0) {
            val skipped = input.skip(left)
            if (skipped > 0) left -= skipped else if (input.read() >= 0) left-- else break
        }
        return input
    }

    /** Adds to the end of [path]; only where [canAppend]. */
    fun openAppend(path: String): OutputStream? = null

    val canAppend: Boolean get() = false

    /** Null: nothing there, or the backend cannot tell. */
    fun stat(path: String): FileStat? = null

    fun setModified(path: String, millis: Long) {}

    /** Renames over an existing file, atomically where the storage can. */
    fun replace(from: String, to: String) {
        runCatching { removeFile(to) }
        rename(from, to)
    }

    fun chown(path: String, uid: Int, gid: Int): Unit = throw UnsupportedOperationException()

    /**
     * Puts the finished [temp] in the place of the file at [path], which stays the same file to everyone
     * else: same mode, same owner. Where that cannot be kept (someone else's file, a link) [temp] is written
     * into the file itself instead: that is not atomic, but it changes nothing but the contents.
     */
    fun replaceKeeping(temp: String, path: String) {
        val old = stat(path) ?: return replace(temp, path)
        val kept = !old.isLink && runCatching {
            val new = stat(temp)
            if (old.uid != null && old.gid != null && (new?.uid != old.uid || new.gid != old.gid)) chown(temp, old.uid, old.gid)
            // After chown, which clears the setuid bits.
            old.mode?.let { chmod(temp, it) }
        }.isSuccess
        if (kept) return replace(temp, path)
        openRead(temp).use { input -> openWrite(path).use { input.copyTo(it, 64 * 1024) } }
        removeFile(temp)
    }

    fun createFile(path: String) = openWrite(path).close()

    /** Where a link points; null if unknown. */
    fun readLink(path: String): String? = null

    /** Runs a shell command on the server; null where there is no such thing. */
    fun exec(command: String, timeoutMinutes: Long = 2): ExecResult? = null

    val canExec: Boolean get() = false

    fun chmod(path: String, mode: Int): Unit = throw UnsupportedOperationException(str(R.string.changing_permissions_is_not_supported))

    /** A symbolic link at [linkPath] to [target]. */
    fun symlink(target: String, linkPath: String): Unit = throw UnsupportedOperationException(str(R.string.links_not_supported))

    /** Recursive delete that removes links themselves, never what they point to. */
    fun delete(item: FileItem) {
        if (item.isDirectory && !item.isSymlink) {
            list(item.path).forEach { delete(it) }
            removeDir(item.path)
        } else {
            removeFile(item.path)
        }
    }

    /** Copy inside this backend without streaming through the app; false = not supported. */
    fun copyWithin(source: FileItem, destPath: String): Boolean = false

    /** Reached over a network: reconnects by itself after a lost connection. */
    val isRemote: Boolean get() = false

    /** Holds a live connection to a server. */
    val isOpen: Boolean get() = false

    override fun close() {}
}

/** `cp` on the server: nothing travels to the phone and back. False if the server cannot do it. */
fun FileSystem.copyOnServer(source: FileItem, destPath: String): Boolean =
    canExec && exec("cp -a -- ${shellQuote(source.path)} ${shellQuote(destPath)}", timeoutMinutes = 24 * 60)?.code == 0

fun isSafeName(name: String) =
    name.isNotBlank() && name != "." && name != ".." && '/' !in name && '\\' !in name && '\u0000' !in name

fun checkName(name: String) {
    if (!isSafeName(name)) throw IOException(str(R.string.invalid_name, name))
}

/** [name], or "name (1).ext" and so on, the first not in [taken]. */
fun uniqueName(name: String, taken: Set<String>): String {
    if (name !in taken) return name
    val dot = name.lastIndexOf('.').takeIf { it > 0 } ?: name.length
    val base = name.substring(0, dot)
    val ext = name.substring(dot)
    return generateSequence(1) { it + 1 }.map { "$base ($it)$ext" }.first { it !in taken }
}

fun joinPath(dir: String, name: String): String = if (dir.endsWith("/")) "$dir$name" else "$dir/$name"

fun parentPath(path: String): String {
    val trimmed = path.trimEnd('/')
    val idx = trimmed.lastIndexOf('/')
    return if (idx <= 0) "/" else trimmed.substring(0, idx)
}

/** Quotes a string for sh: safe for any file name, including quotes, `$` and backticks. */
fun shellQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"

fun permissionString(mode: Int, isDir: Boolean, isLink: Boolean = false): String = buildString {
    append(if (isLink) 'l' else if (isDir) 'd' else '-')
    val chars = "rwxrwxrwx"
    for (i in 0 until 9) append(if (mode and (1 shl (8 - i)) != 0) chars[i] else '-')
}
