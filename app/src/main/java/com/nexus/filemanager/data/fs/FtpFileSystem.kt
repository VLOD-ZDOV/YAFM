package com.nexus.filemanager.data.fs

import com.nexus.filemanager.data.model.FileItem
import com.nexus.filemanager.data.model.ServerConfig
import com.nexus.filemanager.data.model.StorageType
import org.apache.commons.net.ftp.FTP
import org.apache.commons.net.ftp.FTPClient
import org.apache.commons.net.ftp.FTPFile
import org.apache.commons.net.ftp.FTPReply
import java.io.FilterInputStream
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * FTP (commons-net). A client handles one command at a time, so commands share one locked connection
 * and each stream gets a connection of its own.
 */
class FtpFileSystem(private val config: ServerConfig) : FileSystem {

    private var control: FTPClient? = null

    private fun connect(): FTPClient {
        val ftp = FTPClient()
        ftp.connectTimeout = 10_000
        ftp.controlEncoding = "UTF-8"
        ftp.connect(config.host, config.port)
        if (!FTPReply.isPositiveCompletion(ftp.replyCode)) {
            ftp.disconnect()
            throw IOException("FTP-сервер отклонил подключение (код ${ftp.replyCode})")
        }
        val anonymous = config.isAnonymous || config.username.isEmpty()
        if (!ftp.login(if (anonymous) "anonymous" else config.username, if (anonymous) "anonymous" else config.password)) {
            ftp.disconnect()
            throw IOException("FTP: неверный логин или пароль")
        }
        ftp.enterLocalPassiveMode()
        ftp.setFileType(FTP.BINARY_FILE_TYPE)
        return ftp
    }

    /** A line break in a path would end the FTP command and start another one chosen by the file's name. */
    private fun checked(path: String): String {
        if ('\r' in path || '\n' in path) throw IOException("Имя с переводом строки не поддерживается по FTP")
        return path
    }

    @Synchronized
    private fun <T> command(block: (FTPClient) -> T): T {
        val ftp = control?.takeIf { it.isConnected && runCatching { it.sendNoOp() }.getOrDefault(false) }
            ?: connect().also { control = it }
        return block(ftp)
    }

    private fun FTPClient.check(ok: Boolean) {
        if (!ok) throw IOException(replyString?.trim() ?: "Ошибка FTP")
    }

    override fun list(dir: String): List<FileItem> = command { ftp ->
        val path = dir.ifEmpty { "/" }
        ftp.listFiles(checked(path)).filter { it.name != "." && it.name != ".." }.map { it.toItem(path) }
    }

    private fun FTPFile.toItem(dir: String): FileItem {
        val mode = (0..2).sumOf { who ->
            val shift = (2 - who) * 3
            (if (hasPermission(who, FTPFile.READ_PERMISSION)) 4 shl shift else 0) +
                (if (hasPermission(who, FTPFile.WRITE_PERMISSION)) 2 shl shift else 0) +
                (if (hasPermission(who, FTPFile.EXECUTE_PERMISSION)) 1 shl shift else 0)
        }
        return FileItem(
            name = name,
            path = joinPath(dir, name),
            size = if (isDirectory) 0L else size,
            isDirectory = isDirectory,
            lastModified = timestamp?.timeInMillis ?: 0L,
            permissions = permissionString(mode, isDirectory, isSymbolicLink),
            owner = user ?: "",
            group = group ?: "",
            storageType = StorageType.FTP,
            serverId = config.id,
            isSymlink = isSymbolicLink
        )
    }

    override fun openRead(path: String): InputStream {
        val ftp = connect()
        val stream = ftp.retrieveFileStream(checked(path)) ?: run {
            val reply = ftp.replyString
            ftp.disconnect()
            throw IOException(reply?.trim() ?: "Не удалось открыть файл")
        }
        return object : FilterInputStream(stream) {
            override fun close() {
                try { super.close(); ftp.completePendingCommand() } finally { runCatching { ftp.disconnect() } }
            }
        }
    }

    override fun openWrite(path: String): OutputStream {
        val ftp = connect()
        val stream = ftp.storeFileStream(checked(path)) ?: run {
            val reply = ftp.replyString
            ftp.disconnect()
            throw IOException(reply?.trim() ?: "Не удалось записать файл")
        }
        return object : FilterOutputStream(stream) {
            override fun write(b: ByteArray, off: Int, len: Int) = out.write(b, off, len)
            override fun close() {
                try {
                    super.close()
                    ftp.check(ftp.completePendingCommand())
                } finally {
                    runCatching { ftp.disconnect() }
                }
            }
        }
    }

    override fun mkdir(path: String) = command { it.check(it.makeDirectory(checked(path))) }
    override fun rename(from: String, to: String) = command { it.check(it.rename(checked(from), checked(to))) }
    override fun removeFile(path: String) = command { it.check(it.deleteFile(checked(path))) }
    override fun removeDir(path: String) = command { it.check(it.removeDirectory(checked(path))) }

    override fun chmod(path: String, mode: Int) = command {
        it.check(it.sendSiteCommand("CHMOD ${Integer.toOctalString(mode)} ${checked(path)}"))
    }

    @Synchronized
    override fun close() {
        control?.let { runCatching { it.logout() }; runCatching { it.disconnect() } }
        control = null
    }
}
