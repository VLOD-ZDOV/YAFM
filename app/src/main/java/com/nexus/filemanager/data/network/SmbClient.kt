package com.nexus.filemanager.data.network

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import com.nexus.filemanager.data.model.FileItem
import com.nexus.filemanager.data.model.ServerConfig
import com.nexus.filemanager.data.model.StorageType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.EnumSet

class SmbClient(private val config: ServerConfig) {

    private val client = SMBClient()

    private fun getShare(): Pair<Connection, DiskShare> {
        val connection = client.connect(config.host, config.port)
        val auth = if (config.isAnonymous || config.username.isEmpty()) {
            AuthenticationContext.anonymous()
        } else {
            AuthenticationContext(config.username, config.password.toCharArray(), "")
        }
        val session: Session = connection.authenticate(auth)
        val shareName = config.shareName.ifEmpty { "public" }
        val share = session.connectShare(shareName) as DiskShare
        return Pair(connection, share)
    }

    suspend fun testConnection(): Boolean = withContext(Dispatchers.IO) {
        try {
            val (conn, share) = getShare()
            val sharesOk = share.isConnected
            conn.close()
            sharesOk
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    suspend fun listFiles(relativePath: String): List<FileItem> = withContext(Dispatchers.IO) {
        val (conn, share) = getShare()
        try {
            val cleanPath = relativePath.trim('/').replace('/', '\\')
            val fileList = share.list(cleanPath)

            fileList.filter { it.fileName != "." && it.fileName != ".." }.map { info ->
                val isDir = (info.fileAttributes and 0x10L) != 0L // FILE_ATTRIBUTE_DIRECTORY
                val fullPath = if (relativePath.endsWith("/")) "$relativePath${info.fileName}" else "$relativePath/${info.fileName}"
                FileItem(
                    name = info.fileName,
                    path = fullPath,
                    size = if (isDir) 0L else info.endOfFile,
                    isDirectory = isDir,
                    lastModified = info.changeTime?.toEpochMillis() ?: System.currentTimeMillis(),
                    permissions = if (isDir) "rwxr-xr-x" else "rw-r--r--",
                    storageType = StorageType.SMB,
                    serverId = config.id
                )
            }.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
        } finally {
            try { conn.close() } catch (_: Exception) {}
        }
    }

    suspend fun readFile(relativePath: String): String = withContext(Dispatchers.IO) {
        val (conn, share) = getShare()
        try {
            val cleanPath = relativePath.trim('/').replace('/', '\\')
            val smbFile = share.openFile(
                cleanPath,
                EnumSet.of(AccessMask.GENERIC_READ),
                null,
                SMB2ShareAccess.ALL,
                SMB2CreateDisposition.FILE_OPEN,
                null
            )
            val inputStream: InputStream = smbFile.inputStream
            val outputStream = ByteArrayOutputStream()
            inputStream.copyTo(outputStream)
            smbFile.close()
            outputStream.toString(Charsets.UTF_8.name())
        } finally {
            try { conn.close() } catch (_: Exception) {}
        }
    }

    suspend fun writeFile(relativePath: String, content: String): Boolean = withContext(Dispatchers.IO) {
        val (conn, share) = getShare()
        try {
            val cleanPath = relativePath.trim('/').replace('/', '\\')
            val smbFile = share.openFile(
                cleanPath,
                EnumSet.of(AccessMask.GENERIC_WRITE),
                null,
                SMB2ShareAccess.ALL,
                SMB2CreateDisposition.FILE_OVERWRITE_IF,
                null
            )
            val outputStream = smbFile.outputStream
            outputStream.write(content.toByteArray(Charsets.UTF_8))
            outputStream.flush()
            outputStream.close()
            smbFile.close()
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        } finally {
            try { conn.close() } catch (_: Exception) {}
        }
    }

    suspend fun delete(relativePath: String, isDirectory: Boolean): Boolean = withContext(Dispatchers.IO) {
        val (conn, share) = getShare()
        try {
            val cleanPath = relativePath.trim('/').replace('/', '\\')
            if (isDirectory) {
                share.rmdir(cleanPath, true)
            } else {
                share.rm(cleanPath)
            }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        } finally {
            try { conn.close() } catch (_: Exception) {}
        }
    }

    suspend fun createDirectory(relativePath: String): Boolean = withContext(Dispatchers.IO) {
        val (conn, share) = getShare()
        try {
            val cleanPath = relativePath.trim('/').replace('/', '\\')
            share.mkdir(cleanPath)
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        } finally {
            try { conn.close() } catch (_: Exception) {}
        }
    }
}
