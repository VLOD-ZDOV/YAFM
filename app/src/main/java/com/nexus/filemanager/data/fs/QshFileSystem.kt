package com.nexus.filemanager.data.fs

import com.nexus.filemanager.data.model.FileItem
import com.nexus.filemanager.data.model.ServerConfig
import com.nexus.filemanager.data.model.StorageType
import com.nexus.filemanager.data.qsh.Qsh
import com.nexus.filemanager.data.qsh.SftpChannel
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** Browsing over the server's sftp subsystem through qsh (QUIC, TCP fallback); bulk copies use `qsh cp`. */
class QshFileSystem(
    private val config: ServerConfig,
    private val qsh: Qsh
) : FileSystem {

    private var process: Process? = null
    private var channel: SftpChannel? = null

    /** A one-time code for the next login, used once. */
    @Volatile
    var pendingCode: String? = null

    @Synchronized
    private fun sftp(): SftpChannel {
        channel?.takeIf { process?.isAlive == true }?.let { return it }
        closeConnection()
        val code = pendingCode
        pendingCode = null
        val p = qsh.startSftp(config, code)
        val stderr = StringBuffer()
        Thread {
            // Kept short: only the start explains a failed login.
            runCatching { p.errorStream.bufferedReader().forEachLine { if (stderr.length < 4096) stderr.appendLine(it) } }
        }.apply { isDaemon = true }.start()
        try {
            channel = SftpChannel(p.inputStream, p.outputStream)
        } catch (e: IOException) {
            p.waitFor()
            p.destroy()
            val err = stderr.toString().trim()
            Qsh.CodeRequired.from(err)?.let { throw it }
            throw IOException(err.ifEmpty { e.message ?: "qsh: не удалось подключиться" })
        }
        process = p
        return channel!!
    }

    fun home(): String = sftp().realpath(".")

    override fun list(dir: String): List<FileItem> {
        val ch = sftp()
        return ch.list(dir).map { e ->
            val path = joinPath(dir, e.name)
            val attrs = if (e.attrs.isLink) runCatching { ch.stat(path) }.getOrDefault(e.attrs) else e.attrs
            val isDir = attrs.isDirectory
            FileItem(
                name = e.name,
                path = path,
                size = if (isDir) 0L else attrs.size ?: 0L,
                isDirectory = isDir,
                lastModified = (attrs.mtime ?: 0L) * 1000L,
                permissions = permissionString((attrs.permissions ?: 0) and 0x1ff, isDir, e.attrs.isLink && !isDir),
                owner = attrs.uid?.toString() ?: "",
                group = attrs.gid?.toString() ?: "",
                storageType = StorageType.QSH,
                serverId = config.id,
                isSymlink = e.attrs.isLink
            )
        }
    }

    override fun openRead(path: String): InputStream = sftp().openInputStream(path)
    override fun openWrite(path: String): OutputStream = sftp().openOutputStream(path)
    override fun createFile(path: String) = sftp().openOutputStream(path, exclusive = true).close()
    override fun mkdir(path: String) = sftp().mkdir(path)
    override fun rename(from: String, to: String) = sftp().rename(from, to)
    override fun removeFile(path: String) = sftp().remove(path)
    override fun removeDir(path: String) = sftp().rmdir(path)
    override fun chmod(path: String, mode: Int) = sftp().chmod(path, mode)

    // The sftp session is up first, so `qsh cp` rides on its connection instead of logging in again.
    fun upload(localPath: String, isDirectory: Boolean, remoteDir: String) {
        sftp()
        qsh.upload(config, localPath, remoteDir, isDirectory)
    }

    fun download(remote: FileItem, localDir: String) {
        sftp()
        qsh.download(config, remote.path, localDir, remote.isDirectory)
    }

    private fun closeConnection() {
        channel?.close()
        channel = null
        process?.destroy()
        process = null
    }

    @Synchronized
    override fun close() = closeConnection()
}
