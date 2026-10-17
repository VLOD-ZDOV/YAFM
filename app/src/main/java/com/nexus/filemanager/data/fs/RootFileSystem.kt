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

    private companion object {
        const val MULTILINE = "__multiline_names__"
    }

    private fun run(command: String): RootCommander.CommandResult {
        val result = RootCommander.executeBlocking(command)
        if (!result.isSuccess) {
            throw IOException(result.stderr.joinToString(" ").ifBlank { "Команда root завершилась с ошибкой" })
        }
        return result
    }

    override fun list(dir: String): List<FileItem> {
        val q = shellQuote(dir)
        // -H follows only `dir` itself; entries are lstat-ed, so links are seen as links.
        val find = "find -H $q -mindepth 1 -maxdepth 1"
        // Output is one line per entry, so a name with a line break could pass for another entry:
        // such directories take the slower exact path, with names ended by NUL.
        val result = RootCommander.executeBlocking(
            "if $find -name '*\n*' | grep -q .; then echo $MULTILINE; " +
                "else $find -exec stat -c '%f|%s|%Y|%U|%G|%n' {} + 2>/dev/null; fi"
        )
        val items = when {
            result.stdout.firstOrNull() == MULTILINE -> listExact(find)
            !result.isSuccess && result.stdout.isEmpty() -> {
                // An empty directory also lands here on some toybox versions.
                if (RootCommander.executeBlocking("[ -d $q ]").isSuccess) return emptyList()
                throw IOException("Не удалось открыть $dir")
            }
            else -> result.stdout.mapNotNull { line ->
                val parts = line.split('|', limit = 6)
                if (parts.size < 6) null else parseStat(parts.take(5), parts[5])
            }
        }.filter { parentPath(it.path) == parentPath(joinPath(dir, it.name)) }

        val links = items.filter { it.isSymlink }
        if (links.isEmpty()) return items
        // Which links point to directories, so they open like one; answered by index, not by name.
        val dirLinks = RootCommander.executeBlocking(
            "i=0; for p in ${links.joinToString(" ") { shellQuote(it.path) }}; do [ -d \"\$p\" ] && echo \$i; i=\$((i+1)); done"
        ).stdout.mapNotNull { it.trim().toIntOrNull() }.mapNotNull { links.getOrNull(it)?.path }.toSet()
        return items.map { if (it.isSymlink && it.path in dirLinks) it.copy(isDirectory = true, size = 0L) else it }
    }

    /** Per entry: its `stat` line, then its path ended by NUL, which no name can contain. */
    private fun listExact(find: String): List<FileItem> {
        // stat and printf in one child, one after the other: find's own -print0 would be buffered apart from them.
        val process = RootCommander.openProcess(
            "$find -exec sh -c 'stat -c \"%f|%s|%Y|%U|%G\" \"\$1\" && printf \"%s\\0\" \"\$1\"' sh {} \\; 2>/dev/null"
        )
        process.outputStream.close()
        val data = process.inputStream.use { it.readBytes() }
        process.waitFor()
        process.destroy()
        val items = mutableListOf<FileItem>()
        var pos = 0
        while (pos < data.size) {
            val nl = data.indexOfFrom('\n'.code.toByte(), pos)
            val nul = if (nl < 0) -1 else data.indexOfFrom(0, nl + 1)
            if (nul < 0) break
            val fields = String(data, pos, nl - pos, Charsets.UTF_8).split('|')
            parseStat(fields, String(data, nl + 1, nul - nl - 1, Charsets.UTF_8))?.let { items += it }
            pos = nul + 1
        }
        return items
    }

    private fun ByteArray.indexOfFrom(b: Byte, from: Int): Int {
        for (i in from until size) if (this[i] == b) return i
        return -1
    }

    /** `stat` fields %f, %s, %Y, %U, %G and the entry's full path. */
    private fun parseStat(fields: List<String>, path: String): FileItem? {
        if (fields.size < 5) return null
        val mode = fields[0].toIntOrNull(16) ?: return null
        val isDir = mode and 0xF000 == 0x4000
        val isLink = mode and 0xF000 == 0xA000
        val name = path.substringAfterLast('/')
        if (name.isEmpty()) return null
        return FileItem(
            name = name,
            path = path,
            size = if (isDir) 0L else fields[1].toLongOrNull() ?: 0L,
            isDirectory = isDir,
            lastModified = (fields[2].toLongOrNull() ?: 0L) * 1000L,
            permissions = permissionString(mode and 0x1ff, isDir, isLink),
            owner = fields[3],
            group = fields[4],
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
