package com.nexus.filemanager.data.network

import com.nexus.filemanager.data.model.FileItem
import com.nexus.filemanager.data.model.ServerConfig
import com.nexus.filemanager.data.model.StorageType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.net.ftp.FTP
import org.apache.commons.net.ftp.FTPClient
import org.apache.commons.net.ftp.FTPReply
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class FtpClient(private val config: ServerConfig) {

    private fun connectClient(): FTPClient {
        val ftp = FTPClient()
        ftp.connectTimeout = 10000
        ftp.connect(config.host, config.port)

        val reply = ftp.replyCode
        if (!FTPReply.isPositiveCompletion(reply)) {
            ftp.disconnect()
            throw Exception("FTP server refused connection with code: $reply")
        }

        val user = if (config.isAnonymous || config.username.isEmpty()) "anonymous" else config.username
        val pass = if (config.isAnonymous) "anonymous" else config.password
        val loginOk = ftp.login(user, pass)
        if (!loginOk) {
            ftp.disconnect()
            throw Exception("FTP authentication failed")
        }

        ftp.enterLocalPassiveMode()
        ftp.setFileType(FTP.BINARY_FILE_TYPE)
        return ftp
    }

    suspend fun testConnection(): Boolean = withContext(Dispatchers.IO) {
        try {
            val ftp = connectClient()
            val isConnected = ftp.isConnected
            ftp.logout()
            ftp.disconnect()
            isConnected
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    suspend fun listFiles(remotePath: String): List<FileItem> = withContext(Dispatchers.IO) {
        val ftp = connectClient()
        try {
            val path = if (remotePath.isEmpty()) "/" else remotePath
            val files = ftp.listFiles(path)
            val items = mutableListOf<FileItem>()

            for (file in files) {
                val name = file.name
                if (name == "." || name == "..") continue

                val isDir = file.isDirectory
                val size = if (isDir) 0L else file.size
                val mtime = file.timestamp?.timeInMillis ?: System.currentTimeMillis()
                val fullPath = if (path.endsWith("/")) "$path$name" else "$path/$name"

                items.add(
                    FileItem(
                        name = name,
                        path = fullPath,
                        size = size,
                        isDirectory = isDir,
                        lastModified = mtime,
                        permissions = if (isDir) "rwxr-xr-x" else "rw-r--r--",
                        owner = file.user ?: "root",
                        group = file.group ?: "root",
                        storageType = StorageType.FTP,
                        serverId = config.id
                    )
                )
            }

            items.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
        } finally {
            try { ftp.logout() } catch (_: Exception) {}
            try { ftp.disconnect() } catch (_: Exception) {}
        }
    }

    suspend fun readFile(remotePath: String): String = withContext(Dispatchers.IO) {
        val ftp = connectClient()
        try {
            val baos = ByteArrayOutputStream()
            ftp.retrieveFile(remotePath, baos)
            baos.toString(Charsets.UTF_8.name())
        } finally {
            try { ftp.logout() } catch (_: Exception) {}
            try { ftp.disconnect() } catch (_: Exception) {}
        }
    }

    suspend fun writeFile(remotePath: String, content: String): Boolean = withContext(Dispatchers.IO) {
        val ftp = connectClient()
        try {
            val bais = ByteArrayInputStream(content.toByteArray(Charsets.UTF_8))
            val result = ftp.storeFile(remotePath, bais)
            result
        } catch (e: Exception) {
            e.printStackTrace()
            false
        } finally {
            try { ftp.logout() } catch (_: Exception) {}
            try { ftp.disconnect() } catch (_: Exception) {}
        }
    }

    suspend fun delete(remotePath: String, isDirectory: Boolean): Boolean = withContext(Dispatchers.IO) {
        val ftp = connectClient()
        try {
            if (isDirectory) {
                ftp.removeDirectory(remotePath)
            } else {
                ftp.deleteFile(remotePath)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            false
        } finally {
            try { ftp.logout() } catch (_: Exception) {}
            try { ftp.disconnect() } catch (_: Exception) {}
        }
    }

    suspend fun createDirectory(remotePath: String): Boolean = withContext(Dispatchers.IO) {
        val ftp = connectClient()
        try {
            ftp.makeDirectory(remotePath)
        } catch (e: Exception) {
            e.printStackTrace()
            false
        } finally {
            try { ftp.logout() } catch (_: Exception) {}
            try { ftp.disconnect() } catch (_: Exception) {}
        }
    }
}
