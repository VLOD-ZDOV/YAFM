package com.nexus.filemanager.data.qsh

import android.content.Context
import com.nexus.filemanager.data.model.ServerConfig
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Runs the qsh client shipped as `libqsh.so`: Android only lets apps execute files from their native
 * library directory. Its home (keys, known_hosts) lives in the app's private files.
 */
class Qsh(context: Context) {

    private val binary = File(context.applicationInfo.nativeLibraryDir, "libqsh.so")
    private val home = File(context.filesDir, "qsh").apply { mkdirs() }
    private val tmp = context.cacheDir

    val isAvailable: Boolean get() = binary.canExecute()

    /** user@host for qsh's command line; refuses anything qsh could read as an option or a pattern. */
    private fun destination(c: ServerConfig): String {
        if (!USER.matches(c.username)) throw IOException("Недопустимое имя пользователя: ${c.username}")
        if (!HOST.matches(c.host)) throw IOException("Недопустимый адрес сервера: ${c.host}")
        return if (':' in c.host) "${c.username}@[${c.host}]" else "${c.username}@${c.host}"
    }

    private companion object {
        // As qsh's own checks: user names [A-Za-z0-9._-] not starting with '-', hosts without shell or pattern characters.
        val USER = Regex("[A-Za-z0-9._][A-Za-z0-9._-]{0,63}")
        val HOST = Regex("[A-Za-z0-9._:%][A-Za-z0-9._:%-]{0,252}")
    }

    private fun start(args: List<String>): Process {
        if (!isAvailable) throw IOException("qsh не встроен в эту сборку (нет libqsh.so)")
        return ProcessBuilder(listOf(binary.absolutePath) + args)
            .apply {
                environment()["HOME"] = home.absolutePath
                environment()["TMPDIR"] = tmp.absolutePath
            }
            .start()
    }

    /** A running `qsh -s sftp`: its stdin/stdout carry the SFTP protocol. */
    fun startSftp(c: ServerConfig): Process = start(
        listOf("-q", "-p", c.port.toString(), "-o", "BatchMode=yes", "-o", "StrictHostKeyChecking=accept-new",
            "-s", destination(c), "sftp")
    )

    /** `qsh pair`: registers this app's key on the server with a code from `qshd pair` and pins the host key. */
    fun pair(c: ServerConfig, code: String): String = run(listOf("pair", "-p", c.port.toString(), destination(c), code.trim()))

    fun upload(c: ServerConfig, localPath: String, remoteDir: String, recursive: Boolean) =
        copy(c, listOf(localPath, "${destination(c)}:${remoteDir.trimEnd('/')}/"), recursive)

    fun download(c: ServerConfig, remotePath: String, localDir: String, recursive: Boolean) =
        copy(c, listOf("${destination(c)}:$remotePath", localDir.trimEnd('/') + "/"), recursive)

    private fun copy(c: ServerConfig, paths: List<String>, recursive: Boolean) {
        val args = mutableListOf("cp", "-p", c.port.toString(), "--accept-new-host")
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
            throw IOException("qsh: превышено время ожидания")
        }
        errThread.join(1000)
        if (process.exitValue() != 0) throw IOException(stderr.toString().trim().ifEmpty { "qsh: код ${process.exitValue()}" })
        return (stdout + stderr).trim()
    }
}
