package com.yafm.filemanager.data.fs

import com.yafm.filemanager.str
import com.yafm.filemanager.R
import android.system.Os
import com.yafm.filemanager.data.model.FileItem
import com.yafm.filemanager.data.model.StorageType
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files

/** Plain java.io access; falls back to [root] where Android denies access (e.g. Android/data) and root is granted. */
class LocalFileSystem(
    private val root: RootFileSystem,
    private val rootGranted: () -> Boolean
) : FileSystem {

    private inline fun <T> orRoot(action: () -> T, viaRoot: () -> T): T = try {
        action()
    } catch (e: Exception) {
        if (!rootGranted()) throw e
        viaRoot()
    }

    override fun list(dir: String): List<FileItem> {
        val files = File(dir).listFiles()
        if (files == null) {
            if (rootGranted()) return root.list(dir).map { it.copy(storageType = StorageType.LOCAL) }
            throw IOException(str(R.string.no_access_to, dir))
        }
        return files.map { it.toItem() }
    }

    override fun openRead(path: String): InputStream = orRoot({ FileInputStream(path) }, { root.openRead(path) })

    override fun openWrite(path: String): OutputStream = orRoot({ FileOutputStream(path) }, { root.openWrite(path) })

    override fun mkdir(path: String) = orRoot({
        val dir = File(path)
        if (!dir.mkdirs() && !dir.isDirectory) throw IOException(str(R.string.could_not_create_the_folder))
    }, { root.mkdir(path) })

    override fun createFile(path: String) = orRoot({
        if (!File(path).createNewFile()) throw IOException(str(R.string.the_file_already_exists_or_access_is_den))
    }, { root.createFile(path) })

    override fun rename(from: String, to: String) = orRoot({
        if (File(to).exists()) throw IOException(str(R.string.already_exists, File(to).name))
        if (!File(from).renameTo(File(to))) throw IOException(str(R.string.could_not_rename))
    }, { root.rename(from, to) })

    override fun removeFile(path: String) = orRoot({
        if (!File(path).delete()) throw IOException(str(R.string.could_not_delete_2))
    }, { root.removeFile(path) })

    override fun removeDir(path: String) = orRoot({
        if (!File(path).delete()) throw IOException(str(R.string.could_not_delete_the_folder))
    }, { root.removeDir(path) })

    // Not File.deleteRecursively(): it walks into symlinked directories and deletes what they point to.
    override fun delete(item: FileItem) = orRoot({ deleteNoFollow(File(item.path)) }, { root.delete(item) })

    private fun deleteNoFollow(file: File) {
        if (!Files.isSymbolicLink(file.toPath()) && file.isDirectory) {
            file.listFiles()?.forEach { deleteNoFollow(it) }
        }
        if (!file.delete()) throw IOException(str(R.string.could_not_delete, file.name))
    }

    override fun chmod(path: String, mode: Int) = orRoot({ Os.chmod(path, mode) }, { root.chmod(path, mode) })

    private fun File.toItem(): FileItem {
        val isDir = isDirectory
        val isLink = Files.isSymbolicLink(toPath())
        val mode = try { Os.stat(path).st_mode and 0x1ff } catch (_: Exception) { null }
        val perms = if (mode != null) permissionString(mode, isDir) else buildString {
            append(if (isDir) 'd' else '-')
            append(if (canRead()) 'r' else '-')
            append(if (canWrite()) 'w' else '-')
            append(if (canExecute()) 'x' else '-')
        }
        return FileItem(
            name = name,
            path = absolutePath,
            size = if (isDir) 0L else length(),
            isDirectory = isDir,
            lastModified = lastModified(),
            permissions = perms,
            storageType = StorageType.LOCAL,
            isSymlink = isLink
        )
    }
}
