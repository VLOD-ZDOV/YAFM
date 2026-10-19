package com.yafm.filemanager.data.qsh

import com.yafm.filemanager.str
import com.yafm.filemanager.R
import android.content.Context
import com.yafm.filemanager.data.model.ServerConfig
import android.system.Os
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Runs the qsh client shipped as `libqsh.so`: Android only lets apps execute files from their native
 * library directory. Its home (keys, known_hosts) lives in the app's private files.
 */
class Qsh(context: Context) {

    private val binary = File(context.applicationInfo.nativeLibraryDir, "libqsh.so")
    private val askpass = File(context.applicationInfo.nativeLibraryDir, "libaskpass.so")
    private val home = File(context.filesDir, "qsh").apply { mkdirs() }
    private val configDir = File(home, ".config/qsh")
    private val tmp = context.cacheDir

    // Short: a Unix socket path may be at most 107 bytes.
    private val controlDir = File(home, "c")

    init {
        // The sftp session is the master connection (ControlMaster=auto); `qsh cp` finds its socket through
        // this config and reuses the login, so a one-time code is asked once per session, not per copy.
        configDir.mkdirs()
        controlDir.mkdirs()
        listOf(home, File(home, ".config"), configDir, controlDir).forEach { runCatching { Os.chmod(it.path, "700".toInt(8)) } }
        val config = File(configDir, "config")
        val text = "Host *\n  ControlPath ${controlDir.absolutePath}/%C\n"
        if (!config.exists() || config.readText() != text) config.writeText(text)
        // qsh refuses config files others could write.
        runCatching { Os.chmod(config.path, "600".toInt(8)) }
    }

    /** Whether some sftp session is a master that `qsh cp` can ride on (a socket besides the locks). */
    fun hasSharedConnection(): Boolean = controlDir.listFiles()?.any { !it.name.endsWith(".lock") } == true

    /** `-i key` for a server with an imported key; this phone's own key is qsh's default. */
    private fun identity(c: ServerConfig): List<String> =
        if (c.keyPath.isNotEmpty()) listOf("-i", c.keyPath) else emptyList()

    /** This phone's public key, for authorized_keys on a server (made on first use). */
    fun publicKey(): String {
        val pub = File(configDir, "id_ed25519.pub")
        if (!pub.exists()) run(listOf("keygen"))
        return pub.readText().trim()
    }

    val isAvailable: Boolean get() = binary.canExecute()

    /** user@host for qsh's command line; refuses anything qsh could read as an option or a pattern. */
    private fun destination(c: ServerConfig): String {
        if (!USER.matches(c.username)) throw IOException(str(R.string.invalid_user_name, c.username))
        if (!HOST.matches(c.host)) throw IOException(str(R.string.invalid_server_address, c.host))
        return if (':' in c.host) "${c.username}@[${c.host}]" else "${c.username}@${c.host}"
    }

    private companion object {
        // As qsh's own checks: user names [A-Za-z0-9._-] not starting with '-', hosts without shell or pattern characters.
        val USER = Regex("[A-Za-z0-9._][A-Za-z0-9._-]{0,63}")
        val HOST = Regex("[A-Za-z0-9._:%][A-Za-z0-9._:%-]{0,252}")
    }

    /** [answer]: given to qsh through SSH_ASKPASS when the server asks something (a one-time code). */
    private fun start(args: List<String>, answer: String? = null): Process {
        if (!isAvailable) throw IOException(str(R.string.qsh_is_not_included_in_this_build_no_lib))
        return ProcessBuilder(listOf(binary.absolutePath) + args)
            .apply {
                environment()["HOME"] = home.absolutePath
                environment()["TMPDIR"] = tmp.absolutePath
                if (answer != null) {
                    environment()["SSH_ASKPASS"] = askpass.absolutePath
                    environment()["SSH_ASKPASS_REQUIRE"] = "force"
                    environment()["YAFM_ASKPASS_ANSWER"] = answer
                }
            }
            .start()
    }

    /**
     * A running `qsh -s sftp`: its stdin/stdout carry the SFTP protocol. Without [code] qsh never asks
     * anything (BatchMode); a server that wants a one-time code then fails with [CodeRequired.MARKER].
     */
    fun startSftp(c: ServerConfig, code: String? = null): Process = start(
        listOf("-q", "-p", c.port.toString()) + identity(c) + listOf("-o", "BatchMode=${if (code == null) "yes" else "no"}",
            "-o", "StrictHostKeyChecking=accept-new", "-o", "ControlMaster=auto",
            "-s", destination(c), "sftp"),
        code
    )

    /** The server wants an answer (a one-time code) that BatchMode does not let qsh ask for. */
    class CodeRequired(val serverId: String, val prompt: String) : IOException(str(R.string.the_server_asks_for_a_code, prompt)) {
        companion object {
            const val MARKER = "prompting is disabled"

            /** [serverId]: the server that asked, so the code can only go back to it. */
            fun from(serverId: String, stderr: String): CodeRequired? {
                if (MARKER !in stderr) return null
                val prompt = Regex("asks \"([^\"]*)\"").find(stderr)?.groupValues?.get(1)?.trim()
                return CodeRequired(serverId, prompt?.ifEmpty { null } ?: str(R.string.one_time_code))
            }
        }
    }

    /** `qsh pair`: registers this app's key on the server with a code from `qshd pair` and pins the host key. */
    fun pair(c: ServerConfig, code: String): String = run(listOf("pair", "-p", c.port.toString()) + identity(c) + listOf(destination(c), code.trim()))

    fun upload(c: ServerConfig, localPath: String, remoteDir: String, recursive: Boolean) =
        copy(c, listOf(localPath, "${destination(c)}:${remoteDir.trimEnd('/')}/"), recursive)

    fun download(c: ServerConfig, remotePath: String, localDir: String, recursive: Boolean) =
        copy(c, listOf("${destination(c)}:$remotePath", localDir.trimEnd('/') + "/"), recursive)

    private fun copy(c: ServerConfig, paths: List<String>, recursive: Boolean) {
        val args = mutableListOf("cp", "-p", c.port.toString(), "--accept-new-host")
        args += identity(c)
        if (recursive) args += "-r"
        run(args + "--" + paths, timeoutMinutes = 24 * 60)
    }

    private fun run(args: List<String>, timeoutMinutes: Long = 2): String {
        val process = start(args)
        process.outputStream.close()
        val stderr = StringBuilder()
        val errThread = Thread { stderr.append(process.errorStream.bufferedReader().readText()) }.apply { start() }
        val stdout = process.inputStream.bufferedReader().readText()
        if (!process.waitFor(timeoutMinutes, TimeUnit.MINUTES)) {
            process.destroyForcibly()
            throw IOException(str(R.string.qsh_timed_out))
        }
        errThread.join(1000)
        if (process.exitValue() != 0) throw IOException(stderr.toString().trim().ifEmpty { str(R.string.qsh_exit_code, process.exitValue()) })
        return (stdout + stderr).trim()
    }
}
