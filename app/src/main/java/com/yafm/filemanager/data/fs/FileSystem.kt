package com.yafm.filemanager.data.fs

import com.yafm.filemanager.str
import com.yafm.filemanager.R
import com.yafm.filemanager.data.model.FileItem
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream

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

    fun createFile(path: String) = openWrite(path).close()

    fun chmod(path: String, mode: Int): Unit = throw UnsupportedOperationException(str(R.string.changing_permissions_is_not_supported))

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

    override fun close() {}
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
