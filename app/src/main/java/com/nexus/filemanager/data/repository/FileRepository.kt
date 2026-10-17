package com.nexus.filemanager.data.repository

import android.os.Environment
import android.os.StatFs
import com.nexus.filemanager.data.model.FileItem
import com.nexus.filemanager.data.model.ServerConfig
import com.nexus.filemanager.data.model.StorageStats
import com.nexus.filemanager.data.model.StorageType
import com.nexus.filemanager.data.network.FtpClient
import com.nexus.filemanager.data.network.SftpClient
import com.nexus.filemanager.data.network.SmbClient
import com.nexus.filemanager.data.root.RootCommander
import com.nexus.filemanager.data.root.RootFileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest

class FileRepository {

    suspend fun listFiles(
        path: String,
        storageType: StorageType,
        serverConfig: ServerConfig? = null
    ): List<FileItem> = withContext(Dispatchers.IO) {
        when (storageType) {
            StorageType.LOCAL -> {
                val dir = File(path)
                if (!dir.exists() || !dir.isDirectory) return@withContext emptyList()
                val files = dir.listFiles() ?: return@withContext emptyList()

                files.map { file ->
                    val isDir = file.isDirectory
                    val perms = buildString {
                        append(if (isDir) "d" else "-")
                        append(if (file.canRead()) "r" else "-")
                        append(if (file.canWrite()) "w" else "-")
                        append(if (file.canExecute()) "x" else "-")
                    }
                    FileItem(
                        name = file.name,
                        path = file.absolutePath,
                        size = if (isDir) 0L else file.length(),
                        isDirectory = isDir,
                        lastModified = file.lastModified(),
                        permissions = perms,
                        storageType = StorageType.LOCAL
                    )
                }.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
            }
            StorageType.ROOT -> {
                RootFileProvider.listFiles(path)
            }
            StorageType.SMB -> {
                if (serverConfig == null) emptyList()
                else SmbClient(serverConfig).listFiles(path)
            }
            StorageType.SFTP -> {
                if (serverConfig == null) emptyList()
                else SftpClient(serverConfig).listFiles(path)
            }
            StorageType.FTP -> {
                if (serverConfig == null) emptyList()
                else FtpClient(serverConfig).listFiles(path)
            }
        }
    }

    suspend fun readFile(
        path: String,
        storageType: StorageType,
        serverConfig: ServerConfig? = null
    ): String = withContext(Dispatchers.IO) {
        when (storageType) {
            StorageType.LOCAL -> {
                val file = File(path)
                if (!file.canRead()) {
                    // Try fallback to root if local read fails
                    RootFileProvider.readFile(path)
                } else {
                    file.readText(Charsets.UTF_8)
                }
            }
            StorageType.ROOT -> RootFileProvider.readFile(path)
            StorageType.SMB -> {
                serverConfig?.let { SmbClient(it).readFile(path) } ?: throw Exception("Server config missing")
            }
            StorageType.SFTP -> {
                serverConfig?.let { SftpClient(it).readFile(path) } ?: throw Exception("Server config missing")
            }
            StorageType.FTP -> {
                serverConfig?.let { FtpClient(it).readFile(path) } ?: throw Exception("Server config missing")
            }
        }
    }

    suspend fun writeFile(
        path: String,
        content: String,
        storageType: StorageType,
        serverConfig: ServerConfig? = null
    ): Boolean = withContext(Dispatchers.IO) {
        when (storageType) {
            StorageType.LOCAL -> {
                val file = File(path)
                try {
                    file.writeText(content, Charsets.UTF_8)
                    true
                } catch (e: Exception) {
                    // Escalate to root if permission denied
                    RootFileProvider.writeFile(path, content)
                }
            }
            StorageType.ROOT -> RootFileProvider.writeFile(path, content)
            StorageType.SMB -> serverConfig?.let { SmbClient(it).writeFile(path, content) } ?: false
            StorageType.SFTP -> serverConfig?.let { SftpClient(it).writeFile(path, content) } ?: false
            StorageType.FTP -> serverConfig?.let { FtpClient(it).writeFile(path, content) } ?: false
        }
    }

    suspend fun delete(item: FileItem, serverConfig: ServerConfig? = null): Boolean = withContext(Dispatchers.IO) {
        when (item.storageType) {
            StorageType.LOCAL -> {
                val file = File(item.path)
                val ok = if (file.isDirectory) file.deleteRecursively() else file.delete()
                if (!ok) {
                    RootFileProvider.delete(item.path)
                } else true
            }
            StorageType.ROOT -> RootFileProvider.delete(item.path)
            StorageType.SMB -> serverConfig?.let { SmbClient(it).delete(item.path, item.isDirectory) } ?: false
            StorageType.SFTP -> serverConfig?.let { SftpClient(it).delete(item.path, item.isDirectory) } ?: false
            StorageType.FTP -> serverConfig?.let { FtpClient(it).delete(item.path, item.isDirectory) } ?: false
        }
    }

    suspend fun copy(source: FileItem, targetDir: String, serverConfig: ServerConfig? = null): Boolean = withContext(Dispatchers.IO) {
        val destPath = if (targetDir.endsWith("/")) "$targetDir${source.name}" else "$targetDir/${source.name}"
        when (source.storageType) {
            StorageType.LOCAL -> {
                try {
                    val srcFile = File(source.path)
                    val dstFile = File(destPath)
                    if (srcFile.isDirectory) {
                        srcFile.copyRecursively(dstFile, overwrite = true)
                    } else {
                        srcFile.copyTo(dstFile, overwrite = true)
                    }
                    true
                } catch (e: Exception) {
                    RootFileProvider.copy(source.path, destPath)
                }
            }
            StorageType.ROOT -> RootFileProvider.copy(source.path, destPath)
            else -> false // Network-to-network cross-copy can be done via stream or read/write
        }
    }

    suspend fun move(source: FileItem, targetDir: String, serverConfig: ServerConfig? = null): Boolean = withContext(Dispatchers.IO) {
        val destPath = if (targetDir.endsWith("/")) "$targetDir${source.name}" else "$targetDir/${source.name}"
        when (source.storageType) {
            StorageType.LOCAL -> {
                val srcFile = File(source.path)
                val dstFile = File(destPath)
                val moved = srcFile.renameTo(dstFile)
                if (!moved) {
                    RootFileProvider.move(source.path, destPath)
                } else true
            }
            StorageType.ROOT -> RootFileProvider.move(source.path, destPath)
            else -> false
        }
    }

    suspend fun rename(item: FileItem, newName: String, serverConfig: ServerConfig? = null): Boolean = withContext(Dispatchers.IO) {
        val parent = File(item.path).parent ?: "/"
        val targetPath = if (parent.endsWith("/")) "$parent$newName" else "$parent/$newName"
        when (item.storageType) {
            StorageType.LOCAL -> {
                val srcFile = File(item.path)
                val dstFile = File(targetPath)
                val ok = srcFile.renameTo(dstFile)
                if (!ok) {
                    RootFileProvider.move(item.path, targetPath)
                } else true
            }
            StorageType.ROOT -> RootFileProvider.move(item.path, targetPath)
            else -> false
        }
    }

    suspend fun createDirectory(
        parentPath: String,
        name: String,
        storageType: StorageType,
        serverConfig: ServerConfig? = null
    ): Boolean = withContext(Dispatchers.IO) {
        val fullPath = if (parentPath.endsWith("/")) "$parentPath$name" else "$parentPath/$name"
        when (storageType) {
            StorageType.LOCAL -> {
                val dir = File(fullPath)
                val ok = dir.mkdirs()
                if (!ok) {
                    RootFileProvider.createDirectory(fullPath)
                } else true
            }
            StorageType.ROOT -> RootFileProvider.createDirectory(fullPath)
            StorageType.SMB -> serverConfig?.let { SmbClient(it).createDirectory(fullPath) } ?: false
            StorageType.SFTP -> serverConfig?.let { SftpClient(it).createDirectory(fullPath) } ?: false
            StorageType.FTP -> serverConfig?.let { FtpClient(it).createDirectory(fullPath) } ?: false
        }
    }

    suspend fun createFile(
        parentPath: String,
        name: String,
        storageType: StorageType,
        serverConfig: ServerConfig? = null
    ): Boolean = withContext(Dispatchers.IO) {
        val fullPath = if (parentPath.endsWith("/")) "$parentPath$name" else "$parentPath/$name"
        when (storageType) {
            StorageType.LOCAL -> {
                val file = File(fullPath)
                val ok = file.createNewFile()
                if (!ok) {
                    RootFileProvider.createFile(fullPath)
                } else true
            }
            StorageType.ROOT -> RootFileProvider.createFile(fullPath)
            StorageType.SMB -> serverConfig?.let { SmbClient(it).writeFile(fullPath, "") } ?: false
            StorageType.SFTP -> serverConfig?.let { SftpClient(it).writeFile(fullPath, "") } ?: false
            StorageType.FTP -> serverConfig?.let { FtpClient(it).writeFile(fullPath, "") } ?: false
        }
    }

    suspend fun changePermissions(
        item: FileItem,
        octal: String,
        serverConfig: ServerConfig? = null
    ): Boolean = withContext(Dispatchers.IO) {
        when (item.storageType) {
            StorageType.LOCAL, StorageType.ROOT -> RootCommander.changePermissions(item.path, octal)
            StorageType.SFTP -> {
                serverConfig?.let {
                    val octalInt = octal.toIntOrNull(8) ?: 0x755
                    SftpClient(it).chmod(item.path, octalInt)
                } ?: false
            }
            else -> false
        }
    }

    suspend fun calculateChecksum(item: FileItem, algorithm: String = "MD5"): String = withContext(Dispatchers.IO) {
        try {
            val md = MessageDigest.getInstance(algorithm)
            val file = File(item.path)
            if (!file.canRead() && item.storageType == StorageType.ROOT) {
                val res = RootCommander.execute("${algorithm.lowercase()}sum \"${item.path}\"")
                if (res.isSuccess) {
                    return@withContext res.stdout.firstOrNull()?.substringBefore(" ") ?: "Error"
                }
            }
            FileInputStream(file).use { fis ->
                val buffer = ByteArray(8192)
                var bytesRead: Int
                while (fis.read(buffer).also { bytesRead = it } != -1) {
                    md.update(buffer, 0, bytesRead)
                }
            }
            md.digest().joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
    }

    suspend fun getStorageStats(): List<StorageStats> = withContext(Dispatchers.IO) {
        val list = mutableListOf<StorageStats>()
        try {
            // Internal Storage
            val internalPath = Environment.getExternalStorageDirectory()
            val statInternal = StatFs(internalPath.path)
            val totalInternal = statInternal.totalBytes
            val freeInternal = statInternal.availableBytes
            list.add(
                StorageStats(
                    label = "Основное хранилище",
                    path = internalPath.absolutePath,
                    totalBytes = totalInternal,
                    freeBytes = freeInternal,
                    isRoot = false
                )
            )
        } catch (e: Exception) {
            e.printStackTrace()
        }

        try {
            // Root Partition
            val rootPath = File("/")
            val statRoot = StatFs(rootPath.path)
            val totalRoot = statRoot.totalBytes
            val freeRoot = statRoot.availableBytes
            list.add(
                StorageStats(
                    label = "Системный раздел (Root /)",
                    path = "/",
                    totalBytes = totalRoot,
                    freeBytes = freeRoot,
                    isRoot = true
                )
            )
        } catch (e: Exception) {
            e.printStackTrace()
        }

        list
    }
}
