package com.nexus.filemanager.data.fs

import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import com.jcraft.jsch.SftpATTRS
import com.jcraft.jsch.UserInfo
import com.nexus.filemanager.data.model.FileItem
import com.nexus.filemanager.data.model.ServerConfig
import com.nexus.filemanager.data.model.StorageType
import java.io.File
import java.io.FilterInputStream
import java.io.FilterOutputStream
import java.io.InputStream
import java.io.OutputStream

/** SFTP over SSH (JSch). One SSH session per server; a cheap channel per operation, since channels are not thread-safe. */
class SftpFileSystem(
    private val config: ServerConfig,
    private val knownHosts: File
) : FileSystem {

    private var session: Session? = null

    @Synchronized
    private fun session(): Session {
        session?.takeIf { it.isConnected }?.let { return it }
        if (!knownHosts.exists()) knownHosts.createNewFile()
        val jsch = JSch().apply {
            setKnownHosts(knownHosts.absolutePath)
            // An imported key; the password then doubles as its passphrase.
            if (config.keyPath.isNotEmpty()) {
                if (config.password.isEmpty()) addIdentity(config.keyPath) else addIdentity(config.keyPath, config.password)
            }
        }
        val s = jsch.getSession(config.username, config.host, config.port)
        val withKey = config.keyPath.isNotEmpty()
        if (withKey) {
            // The password field is the key's passphrase then: it must never reach the server as a password.
            s.setConfig("PreferredAuthentications", "publickey")
        } else {
            s.setPassword(config.password)
        }
        // "ask" + this UserInfo = OpenSSH's accept-new: remember unknown keys, refuse changed ones.
        s.setConfig("StrictHostKeyChecking", "ask")
        s.userInfo = AcceptNewHostKeys(if (withKey) "" else config.password)
        s.setServerAliveInterval(15_000)
        s.connect(10_000)
        session = s
        return s
    }

    private fun channel(): ChannelSftp =
        (session().openChannel("sftp") as ChannelSftp).apply { connect(10_000) }

    private inline fun <T> withChannel(block: (ChannelSftp) -> T): T {
        val ch = channel()
        try {
            return block(ch)
        } finally {
            ch.disconnect()
        }
    }

    fun home(): String = withChannel { it.home }

    // JSch reads `*`, `?` and `\` in most paths as a glob: rm("dir/*") would delete every file.
    // mkdir is the exception and takes the path as it is.
    private fun esc(path: String) = path.replace("\\", "\\\\").replace("*", "\\*").replace("?", "\\?")

    override fun list(dir: String): List<FileItem> = withChannel { ch ->
        ch.ls(esc(dir.ifEmpty { "/" }))
            .filterIsInstance<ChannelSftp.LsEntry>()
            .filter { it.filename != "." && it.filename != ".." }
            .map { entry ->
                val path = joinPath(dir, entry.filename)
                val isLink = entry.attrs.isLink
                // Show a symlink to a directory as a directory.
                val attrs = if (isLink) runCatching { ch.stat(esc(path)) }.getOrDefault(entry.attrs) else entry.attrs
                attrs.toItem(entry.filename, path).copy(isSymlink = isLink)
            }
    }

    private fun SftpATTRS.toItem(name: String, path: String) = FileItem(
        name = name,
        path = path,
        size = if (isDir) 0L else size,
        isDirectory = isDir,
        lastModified = mTime.toLong() * 1000L,
        permissions = permissionsString,
        owner = uId.toString(),
        group = gId.toString(),
        storageType = StorageType.SFTP,
        serverId = config.id
    )

    override fun openRead(path: String): InputStream {
        val ch = channel()
        return object : FilterInputStream(ch.get(esc(path))) {
            override fun close() {
                try { super.close() } finally { ch.disconnect() }
            }
        }
    }

    override fun openWrite(path: String): OutputStream {
        val ch = channel()
        return object : FilterOutputStream(ch.put(esc(path), ChannelSftp.OVERWRITE)) {
            override fun write(b: ByteArray, off: Int, len: Int) = out.write(b, off, len)
            override fun close() {
                try { super.close() } finally { ch.disconnect() }
            }
        }
    }

    override fun mkdir(path: String) = withChannel { it.mkdir(path) }
    override fun rename(from: String, to: String) = withChannel { it.rename(esc(from), esc(to)) }
    override fun removeFile(path: String) = withChannel { it.rm(esc(path)) }
    override fun removeDir(path: String) = withChannel { it.rmdir(esc(path)) }
    override fun chmod(path: String, mode: Int) = withChannel { it.chmod(mode, esc(path)) }

    @Synchronized
    override fun close() {
        session?.disconnect()
        session = null
    }

    private class AcceptNewHostKeys(private val password: String) : UserInfo {
        // A refusal makes JSch throw JSchChangedHostKeyException.
        override fun promptYesNo(message: String): Boolean = !message.contains("HAS CHANGED", ignoreCase = true)
        override fun getPassphrase(): String? = null
        override fun getPassword(): String = password
        override fun promptPassword(message: String) = true
        override fun promptPassphrase(message: String) = false
        override fun showMessage(message: String) {}
    }
}
