package com.nexus.filemanager.data.root

import android.util.Log
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

    private fun startSuProcess(): Process {
        detectedSuPath?.let { path ->
            try {
                return ProcessBuilder(path).start()
            } catch (_: Exception) {}
        }
        for (candidate in listOf("su") + SU_PATHS) {
            try {
                val p = ProcessBuilder(candidate).start()
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

    suspend fun execute(command: String, timeoutSeconds: Long = 15): CommandResult = withContext(Dispatchers.IO) {
        val stdout = mutableListOf<String>()
        val stderr = mutableListOf<String>()
        var process: Process? = null

        try {
            process = startSuProcess()
            val os = DataOutputStream(process.outputStream)
            val isReader = BufferedReader(InputStreamReader(process.inputStream))
            val esReader = BufferedReader(InputStreamReader(process.errorStream))

            os.writeBytes("$command\n")
            os.writeBytes("exit\n")
            os.flush()

            val stdoutThread = Thread {
                try {
                    var line: String?
                    while (isReader.readLine().also { line = it } != null) {
                        stdout.add(line!!)
                    }
                } catch (_: Exception) {}
            }

            val stderrThread = Thread {
                try {
                    var line: String?
                    while (esReader.readLine().also { line = it } != null) {
                        stderr.add(line!!)
                    }
                } catch (_: Exception) {}
            }

            stdoutThread.start()
            stderrThread.start()

            val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                return@withContext CommandResult(-1, stdout, listOf("Command timed out"))
            }

            stdoutThread.join(1000)
            stderrThread.join(1000)

            CommandResult(process.exitValue(), stdout, stderr)
        } catch (e: Exception) {
            Log.e(TAG, "Error executing root command: $command", e)
            CommandResult(-1, emptyList(), listOf(e.message ?: "Execution error"))
        } finally {
            process?.destroy()
        }
    }

    suspend fun remountPartition(partition: String = "/", readWrite: Boolean = true): Boolean = withContext(Dispatchers.IO) {
        val mode = if (readWrite) "rw" else "ro"
        val cmd = "mount -o remount,$mode $partition || mount -o remount,$mode /system || su -c 'toybox mount -o remount,$mode /'"
        val result = execute(cmd)
        result.isSuccess
    }

    suspend fun changePermissions(path: String, octal: String): Boolean = withContext(Dispatchers.IO) {
        val result = execute("chmod $octal \"$path\"")
        result.isSuccess
    }

    suspend fun changeOwner(path: String, owner: String, group: String): Boolean = withContext(Dispatchers.IO) {
        val result = execute("chown $owner:$group \"$path\"")
        result.isSuccess
    }
}
