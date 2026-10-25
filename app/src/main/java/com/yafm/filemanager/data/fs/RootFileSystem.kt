package com.yafm.filemanager.data.fs

import com.yafm.filemanager.str
import com.yafm.filemanager.R
import com.yafm.filemanager.data.model.FileItem
import com.yafm.filemanager.data.model.StorageType
import com.yafm.filemanager.data.root.RootCommander
import java.io.FilterInputStream
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/** Everything through `su`. Paths always go through [shellQuote]. */
class RootFileSystem : FileSystem {

    private fun run(command: String): RootCommander.CommandResult {
        val result = RootCommander.executeBlocking(command)
        if (!result.isSuccess) {
            throw IOException(result.stderr.joinToString(" ").ifBlank { str(R.string.the_root_command_failed) })
        }
        return result
    }

    /**
     * Each entry is one record "MODE|SIZE|MTIME|USER|GROUP|PATH" ended by NUL, which no name can contain,
     * and is read as raw bytes: no name can pass for another entry, and names that are not valid UTF-8
     * (which would be shown and acted on as a different name) are left out.
     */
    override fun list(dir: String): List<FileItem> {
        val q = shellQuote(dir)
        // -H follows only `dir` itself; entries are lstat-ed, so links are seen as links.
        val find = "find -H $q -mindepth 1 -maxdepth 1"
        // toybox's find has -printf on current Android; older ones fall back to one stat per entry.
        val process = RootCommander.openProcess(
            "if find /dev/null -maxdepth 0 -printf '' 2>/dev/null; then " +
                "$find -printf '%M|%s|%T@|%u|%g|%p\\0'; " +
                "else $find -exec sh -c 'printf \"%s|%s\\0\" \"\$(stat -c \"%A|%s|%Y|%U|%G\" \"\$1\")\" \"\$1\"' sh {} \\;; " +
                "fi 2>/dev/null"
        )
        process.outputStream.close()
        val data = process.inputStream.use { it.readBytes() }
        process.waitFor()
        process.destroy()
        if (data.isEmpty()) {
            if (RootCommander.executeBlocking("[ -d $q ]").isSuccess) return emptyList()
            throw IOException(str(R.string.could_not_open, dir))
        }

        val items = mutableListOf<FileItem>()
        var pos = 0
        while (pos < data.size) {
            var end = pos
            while (end < data.size && data[end] != 0.toByte()) end++
            strictUtf8(data, pos, end - pos)?.let { record -> parse(record)?.let { items += it } }
            pos = end + 1
        }
        val listed = items.filter { parentPath(it.path) == parentPath(joinPath(dir, it.name)) }

        val links = listed.filter { it.isSymlink }
        if (links.isEmpty()) return listed
        // Which links point to directories, so they open like one; answered by index, not by name.
        val dirLinks = RootCommander.executeBlocking(
            "i=0; for p in ${links.joinToString(" ") { shellQuote(it.path) }}; do [ -d \"\$p\" ] && echo \$i; i=\$((i+1)); done"
        ).stdout.mapNotNull { it.trim().toIntOrNull() }.mapNotNull { links.getOrNull(it)?.path }.toSet()
        return listed.map { if (it.isSymlink && it.path in dirLinks) it.copy(isDirectory = true, size = 0L) else it }
    }

    private fun strictUtf8(data: ByteArray, offset: Int, length: Int): String? = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(data, offset, length))
            .toString()
    } catch (_: CharacterCodingException) {
        null
    }

    /** "MODE|SIZE|MTIME|USER|GROUP|PATH" with MODE as in `ls -l` (e.g. drwxr-xr-x). */
    private fun parse(record: String): FileItem? {
        val f = record.split('|', limit = 6)
        if (f.size < 6 || f[0].length < 10) return null
        val path = f[5]
        val name = path.substringAfterLast('/')
        if (name.isEmpty()) return null
        val isDir = f[0][0] == 'd'
        return FileItem(
            name = name,
            path = path,
            size = if (isDir) 0L else f[1].toLongOrNull() ?: 0L,
            isDirectory = isDir,
            lastModified = ((f[2].toDoubleOrNull() ?: 0.0) * 1000).toLong(),
            permissions = f[0],
            owner = f[3],
            group = f[4],
            storageType = StorageType.ROOT,
            isSymlink = f[0][0] == 'l'
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
        if (code != 0) throw IOException(err.ifEmpty { str(R.string.root_exit_code, code) })
    }

    override fun mkdir(path: String) { run("mkdir -p ${shellQuote(path)}") }
    override fun createFile(path: String) { run("touch ${shellQuote(path)}") }
    override fun rename(from: String, to: String) { run("mv -n ${shellQuote(from)} ${shellQuote(to)}") }
    override fun removeFile(path: String) { run("rm -f ${shellQuote(path)}") }
    override fun removeDir(path: String) { run("rmdir ${shellQuote(path)}") }
    override fun delete(item: FileItem) { run("rm -rf ${shellQuote(item.path)}") }
    override fun symlink(target: String, linkPath: String) { run("ln -s ${shellQuote(target)} ${shellQuote(linkPath)}") }
    override fun chmod(path: String, mode: Int) { run("chmod ${Integer.toOctalString(mode)} ${shellQuote(path)}") }

    override fun copyWithin(source: FileItem, destPath: String): Boolean {
        run("cp -a ${shellQuote(source.path)} ${shellQuote(destPath)}")
        return true
    }
}
