package com.nexus.filemanager.data.network

import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import com.nexus.filemanager.data.model.FileItem
import com.nexus.filemanager.data.model.ServerConfig
import com.nexus.filemanager.data.model.StorageType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Vector

class SftpClient(private val config: ServerConfig) {

    private fun openSession(): Pair<Session, ChannelSftp> {
        val jsch = JSch()
        val session = jsch.getSession(config.username, config.host, config.port)
        session.setPassword(config.password)
        session.setConfig("StrictHostKeyChecking", "no")
        session.connect(10000)

        val channel = session.openChannel("sftp") as ChannelSftp
        channel.connect(10000)
        return Pair(session, channel)
    }

    suspend fun testConnection(): Boolean = withContext(Dispatchers.IO) {
        try {
            val (session, channel) = openSession()
            val connected = channel.isConnected
            channel.disconnect()
            session.disconnect()
            connected
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    suspend fun listFiles(remotePath: String): List<FileItem> = withContext(Dispatchers.IO) {
        val (session, channel) = openSession()
        try {
            val path = if (remotePath.isEmpty()) "/" else remotePath
            val vector: Vector<*> = channel.ls(path)
            val items = mutableListOf<FileItem>()

            for (entry in vector) {
                if (entry is ChannelSftp.LsEntry) {
                    val name = entry.filename
                    if (name == "." || name == "..") continue

                    val attrs = entry.attrs
                    val isDir = attrs.isDir
                    val size = attrs.size
                    val mtime = attrs.mTime.toLong() * 1000L
                    val perms = attrs.permissionsString
                    val fullPath = if (path.endsWith("/")) "$path$name" else "$path/$name"

                    items.add(
                        FileItem(
                            name = name,
                            path = fullPath,
                            size = if (isDir) 0L else size,
                            isDirectory = isDir,
                            lastModified = mtime,
                            permissions = perms,
                            storageType = StorageType.SFTP,
                            serverId = config.id
                        )
                    )
                }
            }

            items.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
        } finally {
            try { channel.disconnect() } catch (_: Exception) {}
            try { session.disconnect() } catch (_: Exception) {}
        }
    }

    suspend fun readFile(remotePath: String): String = withContext(Dispatchers.IO) {
        val (session, channel) = openSession()
        try {
            val baos = ByteArrayOutputStream()
            channel.get(remotePath, baos)
            baos.toString(Charsets.UTF_8.name())
        } finally {
            try { channel.disconnect() } catch (_: Exception) {}
            try { session.disconnect() } catch (_: Exception) {}
        }
    }

    suspend fun writeFile(remotePath: String, content: String): Boolean = withContext(Dispatchers.IO) {
        val (session, channel) = openSession()
        try {
            val bais = ByteArrayInputStream(content.toByteArray(Charsets.UTF_8))
            channel.put(bais, remotePath, ChannelSftp.OVERWRITE)
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        } finally {
            try { channel.disconnect() } catch (_: Exception) {}
            try { session.disconnect() } catch (_: Exception) {}
        }
    }

    suspend fun delete(remotePath: String, isDirectory: Boolean): Boolean = withContext(Dispatchers.IO) {
        val (session, channel) = openSession()
        try {
            if (isDirectory) {
                channel.rmdir(remotePath)
            } else {
                channel.rm(remotePath)
            }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        } finally {
            try { channel.disconnect() } catch (_: Exception) {}
            try { session.disconnect() } catch (_: Exception) {}
        }
    }

    suspend fun createDirectory(remotePath: String): Boolean = withContext(Dispatchers.IO) {
        val (session, channel) = openSession()
        try {
            channel.mkdir(remotePath)
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        } finally {
            try { channel.disconnect() } catch (_: Exception) {}
            try { session.disconnect() } catch (_: Exception) {}
        }
    }

    suspend fun chmod(remotePath: String, permissionsOctal: Int): Boolean = withContext(Dispatchers.IO) {
        val (session, channel) = openSession()
        try {
            channel.chmod(permissionsOctal, remotePath)
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        } finally {
            try { channel.disconnect() } catch (_: Exception) {}
            try { session.disconnect() } catch (_: Exception) {}
        }
    }
}
