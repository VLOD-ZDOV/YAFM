package com.yafm.filemanager.data.fs

import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import com.jcraft.jsch.SftpATTRS
import com.jcraft.jsch.SftpException
import com.jcraft.jsch.UIKeyboardInteractive
import com.jcraft.jsch.UserInfo
import com.yafm.filemanager.R
import com.yafm.filemanager.data.model.FileItem
import com.yafm.filemanager.data.model.ServerConfig
import com.yafm.filemanager.data.model.StorageType
import com.yafm.filemanager.str
import java.io.File
import java.io.FilterInputStream
import java.io.FilterOutputStream
import java.io.InputStream
import java.io.OutputStream

/** SFTP over SSH (JSch). One SSH session per server; a cheap channel per operation, since channels are not thread-safe. */
class SftpFileSystem(
    private val config: ServerConfig,
    private val knownHosts: File,
    private val prompts: LoginPrompts
) : FileSystem {

    private var session: Session? = null

    override val isRemote: Boolean get() = true
    override val isOpen: Boolean get() = session?.isConnected == true
    override val canExec: Boolean get() = true
    override val canAppend: Boolean get() = true

    /** The server's key as `ssh-keygen -lf` and the login question show it. */
    fun fingerprint(): String? = session().hostKey?.key?.let { key ->
        val hash = java.security.MessageDigest.getInstance("SHA-256").digest(java.util.Base64.getDecoder().decode(key))
        "SHA256:" + java.util.Base64.getEncoder().withoutPadding().encodeToString(hash)
    }

    @Synchronized
    private fun session(): Session {
        session?.takeIf { it.isConnected }?.let { return it }
        if (!knownHosts.exists()) knownHosts.createNewFile()
        val client = JSch().apply {
            setKnownHosts(knownHosts.absolutePath)
            // An imported key; the password then doubles as its passphrase.
            if (config.keyPath.isNotEmpty()) {
                if (config.password.isEmpty()) addIdentity(config.keyPath) else addIdentity(config.keyPath, config.password)
            }
        }
        val s = client.getSession(config.username, config.host, config.port)
        val withKey = config.keyPath.isNotEmpty()
        if (withKey) {
            // The password field is the key's passphrase then: it must never reach the server as a password.
            // keyboard-interactive: a server may want a one-time code after the key.
            s.setConfig("PreferredAuthentications", "publickey,keyboard-interactive")
        } else {
            s.setPassword(config.password)
        }
        // "ask": a new or changed server key goes to the user, with its fingerprint.
        s.setConfig("StrictHostKeyChecking", "ask")
        s.userInfo = Login(if (withKey) "" else config.password)
        s.setServerAliveInterval(15_000)
        // A phone's network drops for seconds at a time: a minute of silence before giving up.
        s.serverAliveCountMax = 4
        s.connect(10_000)
        session = s
        return s
    }

    private fun channel(): ChannelSftp {
        fun open() = (session().openChannel("sftp") as ChannelSftp).apply {
            bulkRequests = 64
            connect(10_000)
        }
        // A session may be dead without anything having noticed yet. A failed login is not tried twice.
        val reused = isOpen
        return try {
            open()
        } catch (e: com.jcraft.jsch.JSchException) {
            if (!reused) throw e
            close()
            open()
        }
    }

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

    override fun openRead(path: String, offset: Long): InputStream {
        val ch = channel()
        return object : FilterInputStream(ch.get(esc(path), null, offset)) {
            override fun close() {
                try { super.close() } finally { ch.disconnect() }
            }
        }
    }

    override fun openWrite(path: String): OutputStream = write(path, ChannelSftp.OVERWRITE, 0)

    // RESUME starts at the file's end by itself; the offset would count on top of that.
    override fun openAppend(path: String): OutputStream = write(path, ChannelSftp.RESUME, 0)

    private fun write(path: String, mode: Int, offset: Long): OutputStream {
        val ch = channel()
        return object : FilterOutputStream(ch.put(esc(path), null, mode, offset)) {
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
    override fun symlink(target: String, linkPath: String) = withChannel { it.symlink(esc(target), esc(linkPath)) }

    override fun stat(path: String): FileStat? = withChannel { ch ->
        val attrs = try {
            ch.lstat(esc(path))
        } catch (e: SftpException) {
            if (e.id == ChannelSftp.SSH_FX_NO_SUCH_FILE) return@withChannel null
            throw e
        }
        FileStat(attrs.size, attrs.isDir, attrs.permissions and 0xfff, attrs.uId, attrs.gId, attrs.isLink)
    }

    override fun chown(path: String, uid: Int, gid: Int) = withChannel {
        it.chown(uid, esc(path))
        it.chgrp(gid, esc(path))
    }

    override fun setModified(path: String, millis: Long) = withChannel { it.setMtime(esc(path), (millis / 1000).toInt()) }
    override fun readLink(path: String): String = withChannel { it.readlink(esc(path)) }

    // JSch renames with posix-rename where the server has it, which takes a name over.
    override fun replace(from: String, to: String) = withChannel { ch ->
        if (ch.getExtension("posix-rename@openssh.com") == null) runCatching { ch.rm(esc(to)) }
        ch.rename(esc(from), esc(to))
    }

    override fun copyWithin(source: FileItem, destPath: String): Boolean = copyOnServer(source, destPath)

    override fun exec(command: String, timeoutMinutes: Long): ExecResult {
        val ch = session().openChannel("exec") as ChannelExec
        ch.setCommand(command)
        val errors = java.io.ByteArrayOutputStream()
        ch.setErrStream(errors)
        val input = ch.inputStream
        try {
            ch.connect(10_000)
            val output = input.bufferedReader().readText()
            val deadline = System.currentTimeMillis() + timeoutMinutes * 60_000
            // The output may end before the exit status arrives.
            while (!ch.isClosed) {
                if (System.currentTimeMillis() > deadline) throw java.io.IOException(str(R.string.the_server_does_not_answer))
                Thread.sleep(50)
            }
            return ExecResult(ch.exitStatus, output, errors.toString())
        } finally {
            ch.disconnect()
        }
    }

    @Synchronized
    override fun close() {
        session?.disconnect()
        session = null
    }

    private inner class Login(private val password: String) : UserInfo, UIKeyboardInteractive {
        // A refusal makes JSch throw JSchChangedHostKeyException or JSchUnknownHostKeyException.
        override fun promptYesNo(message: String): Boolean {
            val fingerprint = FINGERPRINT.find(message)?.value ?: return true
            return prompts.trustHostKey(config, fingerprint, message.contains("HAS CHANGED", ignoreCase = true))
        }

        override fun getPassphrase(): String? = null
        override fun getPassword(): String = password
        override fun promptPassword(message: String) = true
        override fun promptPassphrase(message: String) = false
        override fun showMessage(message: String) {}

        private var passwordUsed = false

        // PAM: the saved password answers the password question once, the rest (a code) goes to the user.
        override fun promptKeyboardInteractive(
            destination: String, name: String, instruction: String, prompt: Array<String>, echo: BooleanArray
        ): Array<String>? = Array(prompt.size) { i ->
            if (!passwordUsed && password.isNotEmpty() && prompt[i].contains("assword", ignoreCase = true)) {
                passwordUsed = true
                password
            } else {
                prompts.answer(config, prompt[i].trim()) ?: return null
            }
        }
    }

    private companion object {
        val FINGERPRINT = Regex("SHA256:[A-Za-z0-9+/]+|(?:[0-9a-f]{2}:){15,}[0-9a-f]{2}")
    }
}

/** What a login needs from the user. Both block until the user answers. */
interface LoginPrompts {
    fun trustHostKey(server: ServerConfig, fingerprint: String, changed: Boolean): Boolean

    /** Null: the user gave up. */
    fun answer(server: ServerConfig, prompt: String): String?
}
