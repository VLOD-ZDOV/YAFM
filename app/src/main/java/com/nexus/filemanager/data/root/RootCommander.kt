package com.nexus.filemanager.data.root

import android.util.Log
import com.nexus.filemanager.data.fs.shellQuote
import com.nexus.filemanager.data.model.RootInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.DataOutputStream
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

object RootCommander {
    private const val TAG = "RootCommander"

    private val SU_PATHS = arrayOf(
        "/data/adb/ksu/bin/su",      // KernelSU
        "/data/adb/ap/bin/su",       // APatch
        "/system/bin/su",
        "/system/xbin/su",
        "/sbin/su",
        "/system/sd/xbin/su",
        "/system/bin/failsafe/su",
        "/data/local/xbin/su",
        "/data/local/bin/su",
        "/data/local/su"
    )

    data class CommandResult(
        val exitCode: Int,
        val stdout: List<String>,
        val stderr: List<String>
    ) {
        val isSuccess: Boolean get() = exitCode == 0
        fun stdoutString(): String = stdout.joinToString("\n")
    }

    @Volatile
    private var detectedSuPath: String? = null

    private fun startSuProcess(vararg args: String): Process {
        detectedSuPath?.let { path ->
            try {
                return ProcessBuilder(path, *args).start()
            } catch (_: Exception) {}
        }
        for (candidate in listOf("su") + SU_PATHS) {
            try {
                val p = ProcessBuilder(candidate, *args).start()
                detectedSuPath = candidate
                return p
            } catch (_: Exception) {}
        }
        throw IllegalStateException("No su binary accessible")
    }

    suspend fun checkRoot(): RootInfo = withContext(Dispatchers.IO) {
        val suFile = SU_PATHS.firstOrNull { File(it).exists() }
        val suFound = suFile != null

        val result = execute("id")
        val isGranted = result.isSuccess && result.stdout.any { it.contains("uid=0(root)") || it.contains("0(root)") }

        var implementation = "None"
        var suVersion = ""

        if (isGranted) {
            val vResult = execute("su -v")
            suVersion = if (vResult.isSuccess) vResult.stdout.firstOrNull() ?: "" else ""

            val ksuCheck = execute("ls -d /data/adb/ksu 2>/dev/null || which ksud 2>/dev/null")
            val apCheck = execute("ls -d /data/adb/ap 2>/dev/null")
            val magiskCheck = execute("magisk -v 2>/dev/null")

            if (ksuCheck.isSuccess && ksuCheck.stdout.isNotEmpty()) {
                implementation = "KernelSU"
            } else if (apCheck.isSuccess && apCheck.stdout.isNotEmpty()) {
                implementation = "APatch"
            } else if (magiskCheck.isSuccess || suVersion.contains("MAGISK", ignoreCase = true)) {
                implementation = "Magisk"
            } else if (suVersion.isNotEmpty()) {
                implementation = "SU ($suVersion)"
            } else {
                implementation = "Root Shell"
            }
        }

        RootInfo(
            isRootAvailable = suFound || isGranted,
            isRootGranted = isGranted,
            suVersion = suVersion,
            rootImplementation = implementation
        )
    }

    suspend fun execute(command: String, timeoutSeconds: Long = 15): CommandResult =
        withContext(Dispatchers.IO) { executeBlocking(command, timeoutSeconds) }

    fun executeBlocking(command: String, timeoutSeconds: Long = 15): CommandResult {
        val stdout = java.util.Collections.synchronizedList(mutableListOf<String>())
        val stderr = java.util.Collections.synchronizedList(mutableListOf<String>())
        var process: Process? = null

        try {
            process = startSuProcess()
            val os = DataOutputStream(process.outputStream)
            os.writeBytes("$command\n")
            os.writeBytes("exit\n")
            os.flush()

            val stdoutThread = Thread { drainLines(process.inputStream, stdout) }
            val stderrThread = Thread { drainLines(process.errorStream, stderr) }
            stdoutThread.start()
            stderrThread.start()

            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                return CommandResult(-1, stdout.toList(), listOf("Command timed out"))
            }
            stdoutThread.join(1000)
            stderrThread.join(1000)
            return CommandResult(process.exitValue(), stdout.toList(), stderr.toList())
        } catch (e: Exception) {
            Log.e(TAG, "Error executing root command: $command", e)
            return CommandResult(-1, emptyList(), listOf(e.message ?: "Execution error"))
        } finally {
            process?.destroy()
        }
    }

    /** Runs [command] as root and hands out its raw stdin/stdout, for binary transfers. */
    // `su -c`, not the command on stdin: the shell may read ahead into data meant for the command.
    fun openProcess(command: String): Process = startSuProcess("-c", command)

    private fun drainLines(stream: java.io.InputStream, into: MutableList<String>) {
        try {
            BufferedReader(InputStreamReader(stream)).forEachLine { into.add(it) }
        } catch (_: Exception) {}
    }

    suspend fun changePermissions(path: String, octal: String): Boolean = withContext(Dispatchers.IO) {
        if (!octal.matches(Regex("[0-7]{3,4}"))) return@withContext false
        val result = execute("chmod $octal ${shellQuote(path)}")
        result.isSuccess
    }

    suspend fun changeOwner(path: String, owner: String, group: String): Boolean = withContext(Dispatchers.IO) {
        val result = execute("chown ${shellQuote("$owner:$group")} ${shellQuote(path)}")
        result.isSuccess
    }
}
