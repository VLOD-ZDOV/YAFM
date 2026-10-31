package com.yafm.filemanager.data.fs

import com.yafm.filemanager.str
import com.yafm.filemanager.R
import android.util.Log
import com.yafm.filemanager.data.model.FileItem
import com.yafm.filemanager.data.model.ServerConfig
import com.yafm.filemanager.data.model.StorageType
import com.yafm.filemanager.data.qsh.Qsh
import com.yafm.filemanager.data.qsh.SftpChannel
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * Everything goes over the server's sftp subsystem through qsh (QUIC, TCP fallback): unlike `qsh cp` it
 * reports progress, can be cancelled and goes on after a lost connection.
 * [takeCode]: a one-time code typed for the next login, used once.
 */
class QshFileSystem(
    private val config: ServerConfig,
    private val qsh: Qsh,
    private val takeCode: () -> String?
) : FileSystem {

    private var process: Process? = null
    private var channel: SftpChannel? = null

    override val isRemote: Boolean get() = true
    override val isOpen: Boolean get() = process?.isAlive == true && channel?.isAlive == true

    @Synchronized
    private fun sftp(): SftpChannel {
        channel?.takeIf { isOpen }?.let { return it }
        closeConnection()
        val code = takeCode()
        val p = qsh.startSftp(config, code)
        val stderr = StringBuffer()
        val errors = Thread {
            // Kept short: only the start explains a failed login.
            runCatching {
                p.errorStream.bufferedReader().forEachLine {
                    // qsh's warnings (e.g. why the connection is not shared) go to logcat as well.
                    Log.w("qsh", it)
                    if (stderr.length < 4096) stderr.appendLine(it)
                }
            }
        }.apply { isDaemon = true; start() }
        try {
            channel = SftpChannel(p.inputStream, p.outputStream)
        } catch (e: IOException) {
            p.waitFor()
            p.destroy()
            // The reason may still be on its way; without it a code request would look like any failure.
            errors.join(1000)
            val err = stderr.toString().trim()
            Qsh.CodeRequired.from(config, err)?.let { throw it }
            // The key is checked first: a refusal after a code is about the code.
            if (code != null && "denied" in err) throw Qsh.CodeRequired(config, str(R.string.wrong_code_try_the_next_one))
            throw IOException(err.ifEmpty { e.message ?: str(R.string.qsh_could_not_connect) })
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

    override fun openRead(path: String, offset: Long): InputStream = sftp().openInputStream(path, offset)
    override fun openAppend(path: String): OutputStream = sftp().openAppendStream(path)
    override val canAppend: Boolean get() = true

    override fun stat(path: String): FileStat? {
        val attrs = try {
            sftp().lstat(path)
        } catch (e: SftpChannel.SftpException) {
            if (e.code == SftpChannel.FX_NO_SUCH_FILE) return null
            throw e
        }
        return FileStat(attrs.size ?: 0L, attrs.isDirectory, attrs.permissions?.and(0xfff), attrs.uid, attrs.gid, attrs.isLink)
    }

    override fun chown(path: String, uid: Int, gid: Int) = sftp().chown(path, uid, gid)
    override fun setModified(path: String, millis: Long) = sftp().setMtime(path, millis / 1000)
    override fun replace(from: String, to: String) = sftp().renameOver(from, to)
    override fun readLink(path: String): String = sftp().readlink(path)

    override val canExec: Boolean get() = true

    // The sftp session is up first: it holds the login the command rides on.
    override fun exec(command: String, timeoutMinutes: Long): ExecResult {
        sftp()
        val result = qsh.exec(config, command, timeoutMinutes)
        Qsh.CodeRequired.from(config, result.errors)?.let { throw it }
        return result
    }

    override fun copyWithin(source: FileItem, destPath: String): Boolean = copyOnServer(source, destPath)

    private fun closeConnection() {
        channel?.close()
        channel = null
        process?.destroy()
        process = null
    }

    @Synchronized
    override fun close() = closeConnection()

    /** [close] leaves the background master for later logins; this ends it too. */
    fun logOut() {
        close()
        qsh.closeSharedConnection(config)
    }
}
