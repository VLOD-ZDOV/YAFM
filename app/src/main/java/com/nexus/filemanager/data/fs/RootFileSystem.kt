package com.nexus.filemanager.data.fs

import com.nexus.filemanager.data.model.FileItem
import com.nexus.filemanager.data.model.StorageType
import com.nexus.filemanager.data.root.RootCommander
import java.io.FilterInputStream
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** Everything through `su`. Paths always go through [shellQuote]. */
class RootFileSystem : FileSystem {

    private fun run(command: String): RootCommander.CommandResult {
        val result = RootCommander.executeBlocking(command)
        if (!result.isSuccess) {
            throw IOException(result.stderr.joinToString(" ").ifBlank { "Команда root завершилась с ошибкой" })
        }
        return result
    }

    override fun list(dir: String): List<FileItem> {
        val q = shellQuote(dir)
        // lstat, so links are seen as links (recursive operations must not follow them).
        val result = RootCommander.executeBlocking(
            "find -H $q -mindepth 1 -maxdepth 1 -exec stat -c '%f|%s|%Y|%U|%G|%n' {} + 2>/dev/null"
        )
        if (!result.isSuccess && result.stdout.isEmpty()) {
            // An empty directory also lands here on some toybox versions.
            if (RootCommander.executeBlocking("[ -d $q ]").isSuccess) return emptyList()
            throw IOException("Не удалось открыть $dir")
        }
        val items = result.stdout.mapNotNull { line -> parseStat(line) }
        val links = items.filter { it.isSymlink }
        if (links.isEmpty()) return items
        // Which links point to directories, so they can be opened like one.
        val dirLinks = RootCommander.executeBlocking(
            "for p in ${links.joinToString(" ") { shellQuote(it.path) }}; do [ -d \"\$p\" ] && echo \"\$p\"; done"
        ).stdout.toSet()
        return items.map { if (it.isSymlink && it.path in dirLinks) it.copy(isDirectory = true, size = 0L) else it }
    }

    private fun parseStat(line: String): FileItem? {
        val parts = line.split('|', limit = 6)
        if (parts.size < 6) return null
        val mode = parts[0].toIntOrNull(16) ?: return null
        val isDir = mode and 0xF000 == 0x4000
        val isLink = mode and 0xF000 == 0xA000
        val name = parts[5].substringAfterLast('/')
        if (name.isEmpty()) return null
        return FileItem(
            name = name,
            path = parts[5],
            size = if (isDir) 0L else parts[1].toLongOrNull() ?: 0L,
            isDirectory = isDir,
            lastModified = (parts[2].toLongOrNull() ?: 0L) * 1000L,
            permissions = permissionString(mode and 0x1ff, isDir, isLink),
            owner = parts[3],
            group = parts[4],
            storageType = StorageType.ROOT,
            isSymlink = isLink
        )
    }

    override fun openRead(path: String): InputStream {
        val process = RootCommander.openProcess("cat ${shellQuote(path)}")
        process.outputStream.close()
        return object : FilterInputStream(process.inputStream) {
            override fun close() {
                super.close()
                finish(process)
            }
        }
    }

    override fun openWrite(path: String): OutputStream {
        val process = RootCommander.openProcess("cat > ${shellQuote(path)}")
        return object : FilterOutputStream(process.outputStream) {
            override fun write(b: ByteArray, off: Int, len: Int) = out.write(b, off, len)
            override fun close() {
                super.close()
                finish(process)
            }
        }
    }

    private fun finish(process: Process) {
        val code = process.waitFor()
        val err = process.errorStream.bufferedReader().readText().trim()
        process.destroy()
        if (code != 0) throw IOException(err.ifEmpty { "root: код $code" })
    }

    override fun mkdir(path: String) { run("mkdir -p ${shellQuote(path)}") }
    override fun createFile(path: String) { run("touch ${shellQuote(path)}") }
    override fun rename(from: String, to: String) { run("mv -n ${shellQuote(from)} ${shellQuote(to)}") }
    override fun removeFile(path: String) { run("rm -f ${shellQuote(path)}") }
    override fun removeDir(path: String) { run("rmdir ${shellQuote(path)}") }
    override fun delete(item: FileItem) { run("rm -rf ${shellQuote(item.path)}") }
    override fun chmod(path: String, mode: Int) { run("chmod ${Integer.toOctalString(mode)} ${shellQuote(path)}") }

    override fun copyWithin(source: FileItem, destPath: String): Boolean {
        run("cp -a ${shellQuote(source.path)} ${shellQuote(destPath)}")
        return true
    }
}
