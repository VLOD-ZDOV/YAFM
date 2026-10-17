package com.nexus.filemanager.data.fs

import android.system.Os
import com.nexus.filemanager.data.model.FileItem
import com.nexus.filemanager.data.model.StorageType
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
            throw IOException("Нет доступа к $dir")
        }
        return files.map { it.toItem() }
    }

    override fun openRead(path: String): InputStream = orRoot({ FileInputStream(path) }, { root.openRead(path) })

    override fun openWrite(path: String): OutputStream = orRoot({ FileOutputStream(path) }, { root.openWrite(path) })

    override fun mkdir(path: String) = orRoot({
        val dir = File(path)
        if (!dir.mkdirs() && !dir.isDirectory) throw IOException("Не удалось создать папку")
    }, { root.mkdir(path) })

    override fun createFile(path: String) = orRoot({
        if (!File(path).createNewFile()) throw IOException("Файл уже существует или нет доступа")
    }, { root.createFile(path) })

    override fun rename(from: String, to: String) = orRoot({
        if (File(to).exists()) throw IOException("${File(to).name} уже существует")
        if (!File(from).renameTo(File(to))) throw IOException("Не удалось переименовать")
    }, { root.rename(from, to) })

    override fun removeFile(path: String) = orRoot({
        if (!File(path).delete()) throw IOException("Не удалось удалить")
    }, { root.removeFile(path) })

    override fun removeDir(path: String) = orRoot({
        if (!File(path).delete()) throw IOException("Не удалось удалить папку")
    }, { root.removeDir(path) })

    // Not File.deleteRecursively(): it walks into symlinked directories and deletes what they point to.
    override fun delete(item: FileItem) = orRoot({ deleteNoFollow(File(item.path)) }, { root.delete(item) })

    private fun deleteNoFollow(file: File) {
        if (!Files.isSymbolicLink(file.toPath()) && file.isDirectory) {
            file.listFiles()?.forEach { deleteNoFollow(it) }
        }
        if (!file.delete()) throw IOException("Не удалось удалить ${file.name}")
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
