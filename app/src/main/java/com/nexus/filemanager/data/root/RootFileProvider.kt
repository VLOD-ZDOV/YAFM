package com.nexus.filemanager.data.root

import android.util.Base64
import com.nexus.filemanager.data.model.FileItem
import com.nexus.filemanager.data.model.StorageType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

object RootFileProvider {

    suspend fun listFiles(directoryPath: String): List<FileItem> = withContext(Dispatchers.IO) {
        val safePath = if (directoryPath.endsWith("/")) directoryPath else "$directoryPath/"
        // Use toybox/toolbox stat or ls -la to get accurate permissions, sizes, dates
        val cmd = "ls -la \"$directoryPath\""
        val result = RootCommander.execute(cmd)

        if (!result.isSuccess) {
            // Fallback to simple ls -1
            val fallback = RootCommander.execute("ls -1 \"$directoryPath\"")
            return@withContext fallback.stdout.filter { it.isNotBlank() && it != "." && it != ".." }.map { name ->
                val fullPath = safePath + name
                FileItem(
                    name = name,
                    path = fullPath,
                    size = 0L,
                    isDirectory = false,
                    lastModified = System.currentTimeMillis(),
                    permissions = "rwxr-xr-x",
                    storageType = StorageType.ROOT
                )
            }
        }

        val items = mutableListOf<FileItem>()

        for (line in result.stdout) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("total ") || trimmed.startsWith("итого ")) continue

            // Parse standard ls -la line:
            // drwxr-xr-x  14 root root 4096 Oct 17 12:00 system
            // -rw-r--r--   1 root root  123 Oct 17 12:00 build.prop
            // lrwxrwxrwx   1 root root   11 Oct 17 12:00 etc -> /system/etc
            val tokens = trimmed.split("\\s+".toRegex())
            if (tokens.size < 8) continue

            val perms = tokens[0]
            val isDir = perms.startsWith("d")
            val isSymlink = perms.startsWith("l")

            // Identify owner and group
            val owner = tokens.getOrNull(2) ?: "root"
            val group = tokens.getOrNull(3) ?: "root"

            // Size
            val size = tokens.getOrNull(4)?.toLongOrNull() ?: 0L

            // Name is usually after date tokens (tokens[5], [6], [7])
            val nameRaw = tokens.drop(7).joinToString(" ")
            val name = if (isSymlink && nameRaw.contains(" -> ")) {
                nameRaw.substringBefore(" -> ").trim()
            } else {
                nameRaw.trim()
            }

            if (name == "." || name == ".." || name.isEmpty()) continue

            val fullPath = safePath + name
            items.add(
                FileItem(
                    name = name,
                    path = fullPath,
                    size = size,
                    isDirectory = isDir,
                    lastModified = System.currentTimeMillis(),
                    permissions = perms,
                    owner = owner,
                    group = group,
                    storageType = StorageType.ROOT
                )
            )
        }

        items.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
    }

    suspend fun readFile(path: String): String = withContext(Dispatchers.IO) {
        val result = RootCommander.execute("cat \"$path\"")
        if (result.isSuccess) {
            result.stdoutString()
        } else {
            throw Exception("Failed to read file with root: ${result.stderr.joinToString(" ")}")
        }
    }

    suspend fun writeFile(path: String, content: String): Boolean = withContext(Dispatchers.IO) {
        // Encode to base64 to avoid quote and newline escaping issues in shell
        val b64 = Base64.encodeToString(content.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        val dir = File(path).parent ?: "/"
        // Ensure directory exists, remount rw if needed, and write
        val cmd = "mount -o remount,rw / 2>/dev/null; mount -o remount,rw /system 2>/dev/null; mkdir -p \"$dir\"; echo \"$b64\" | base64 -d > \"$path\""
        val result = RootCommander.execute(cmd)
        result.isSuccess
    }

    suspend fun delete(path: String): Boolean = withContext(Dispatchers.IO) {
        val result = RootCommander.execute("mount -o remount,rw / 2>/dev/null; rm -rf \"$path\"")
        result.isSuccess
    }

    suspend fun copy(source: String, dest: String): Boolean = withContext(Dispatchers.IO) {
        val result = RootCommander.execute("mount -o remount,rw / 2>/dev/null; cp -a \"$source\" \"$dest\"")
        result.isSuccess
    }

    suspend fun move(source: String, dest: String): Boolean = withContext(Dispatchers.IO) {
        val result = RootCommander.execute("mount -o remount,rw / 2>/dev/null; mv \"$source\" \"$dest\"")
        result.isSuccess
    }

    suspend fun createDirectory(path: String): Boolean = withContext(Dispatchers.IO) {
        val result = RootCommander.execute("mount -o remount,rw / 2>/dev/null; mkdir -p \"$path\"")
        result.isSuccess
    }

    suspend fun createFile(path: String): Boolean = withContext(Dispatchers.IO) {
        val result = RootCommander.execute("mount -o remount,rw / 2>/dev/null; touch \"$path\"")
        result.isSuccess
    }
}
