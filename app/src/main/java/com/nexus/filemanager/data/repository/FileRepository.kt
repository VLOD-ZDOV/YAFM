package com.nexus.filemanager.data.repository

import com.nexus.filemanager.str
import com.nexus.filemanager.R
import android.content.Context
import android.os.Environment
import android.os.StatFs
import com.nexus.filemanager.data.fs.FileSystem
import com.nexus.filemanager.data.fs.FtpFileSystem
import com.nexus.filemanager.data.fs.LocalFileSystem
import com.nexus.filemanager.data.fs.QshFileSystem
import com.nexus.filemanager.data.fs.RootFileSystem
import com.nexus.filemanager.data.fs.SftpFileSystem
import com.nexus.filemanager.data.fs.SmbFileSystem
import com.nexus.filemanager.data.fs.joinPath
import com.nexus.filemanager.data.fs.parentPath
import com.nexus.filemanager.data.media.MediaIndex
import com.nexus.filemanager.data.model.FileItem
import com.nexus.filemanager.data.model.Location
import com.nexus.filemanager.data.model.ServerConfig
import com.nexus.filemanager.data.model.StorageStats
import com.nexus.filemanager.data.model.StorageType
import com.nexus.filemanager.data.qsh.Qsh
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/** Single entry point to all storages. Keeps one open connection per server until it is edited or deleted. */
class FileRepository(context: Context) {

    /** Set once root is confirmed; local access then falls back to root where Android refuses it. */
    @Volatile
    var rootGranted = false

    val qsh = Qsh(context)
    val servers = ServerRepository(context) { id -> invalidate(id) }

    private val root = RootFileSystem()
    private val local = LocalFileSystem(root) { rootGranted }
    private val remote = ConcurrentHashMap<String, FileSystem>()
    private val knownHosts = File(context.filesDir, "ssh_known_hosts")
    private val openCache = File(context.cacheDir, "open").apply { deleteRecursively() }
    private val media = MediaIndex(context)

    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }

    private fun create(config: ServerConfig): FileSystem = when (config.type) {
        StorageType.SMB -> SmbFileSystem(config)
        StorageType.SFTP -> SftpFileSystem(config, knownHosts)
        StorageType.FTP -> FtpFileSystem(config)
        StorageType.QSH -> QshFileSystem(config, qsh)
        StorageType.LOCAL, StorageType.ROOT -> error("not a server type")
    }

    private fun fs(type: StorageType, serverId: String?): FileSystem = when (type) {
        StorageType.LOCAL -> local
        StorageType.ROOT -> root
        else -> {
            val id = serverId ?: throw IOException(str(R.string.no_server_given))
            remote.getOrPut(id) {
                create(servers.getServer(id) ?: throw IOException(str(R.string.the_server_was_removed)))
            }
        }
    }

    private fun fs(item: FileItem) = fs(item.storageType, item.serverId)
    private fun fs(location: Location) = fs(location.type, location.server?.id)

    fun invalidate(serverId: String) {
        remote.remove(serverId)?.let { fs -> Thread { runCatching { fs.close() } }.start() }
    }

    // A hostile server could list names like "../x"; they never reach the UI or a local path.
    suspend fun list(location: Location): List<FileItem> = io {
        location.category?.let { return@io media.query(it) }
        fs(location).list(location.path).filter { isSafeName(it.name) }
    }

    /** The configured start directory, else the home directory where the protocol has one. */
    suspend fun startPath(server: ServerConfig): String = io {
        server.remotePath.ifEmpty {
            when (val f = fs(server.type, server.id)) {
                is SftpFileSystem -> f.home()
                is QshFileSystem -> f.home()
                else -> "/"
            }
        }
    }

    suspend fun readText(item: FileItem): String = io {
        val tooBig = IOException(str(R.string.the_file_is_over_mb_open_it_in_another_a, MAX_TEXT_SIZE / 1024 / 1024))
        if (item.size > MAX_TEXT_SIZE) throw tooBig
        // The listed size comes from the server and may be wrong: the limit holds for what is actually read.
        val out = java.io.ByteArrayOutputStream()
        fs(item).openRead(item.path).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                out.write(buffer, 0, n)
                if (out.size() > MAX_TEXT_SIZE) throw tooBig
            }
        }
        out.toString(Charsets.UTF_8.name())
    }

    suspend fun writeText(item: FileItem, text: String) = io {
        fs(item).openWrite(item.path).use { it.write(text.toByteArray(Charsets.UTF_8)) }
    }

    suspend fun delete(item: FileItem) = io { fs(item).delete(item) }

    suspend fun rename(item: FileItem, newName: String) = io {
        checkName(newName)
        fs(item).rename(item.path, joinPath(parentPath(item.path), newName))
    }

    suspend fun createDirectory(location: Location, name: String) = io {
        checkName(name)
        fs(location).mkdir(joinPath(location.path, name))
    }

    suspend fun createFile(location: Location, name: String) = io {
        checkName(name)
        fs(location).createFile(joinPath(location.path, name))
    }

    suspend fun changePermissions(item: FileItem, octal: String) = io {
        val mode = octal.takeIf { it.matches(Regex("[0-7]{3,4}")) }?.toInt(8)
            ?: throw IOException(str(R.string.invalid_permissions, octal))
        fs(item).chmod(item.path, mode)
    }

    /**
     * Copies or moves [items] into [dest], across storages if needed. A name already taken in [dest]
     * gets a " (1)" suffix, so nothing is overwritten. Links to directories are not followed when copying
     * (a link to `.` would never end); returns how many were skipped.
     */
    suspend fun transfer(items: List<FileItem>, dest: Location, move: Boolean, progress: (String) -> Unit): Int = io {
        var skipped = 0
        val dst = fs(dest)
        val taken = dst.list(dest.path).map { it.name }.toMutableSet()
        items.forEachIndexed { i, item ->
            progress("${i + 1}/${items.size}: ${item.name}")
            val src = fs(item)
            val sameFs = src === dst
            if (move && sameFs && parentPath(item.path) == dest.path.trimEnd('/').ifEmpty { "/" }) return@forEachIndexed
            checkName(item.name)
            val name = uniqueName(item.name, taken)
            taken += name
            val target = joinPath(dest.path, name)
            if (sameFs && item.isDirectory && (target + "/").startsWith(item.path.trimEnd('/') + "/")) {
                throw IOException(str(R.string.folder_cannot_be_copied_into_itself, item.name))
            }
            when {
                move && sameFs -> src.rename(item.path, target)
                sameFs && src.copyWithin(item, target) -> Unit
                src is QshFileSystem && dst is LocalFileSystem && name == item.name -> src.download(item, dest.path)
                dst is QshFileSystem && src is LocalFileSystem && name == item.name ->
                    dst.upload(item.path, item.isDirectory, dest.path)
                else -> skipped += copyTree(src, item, dst, target)
            }
            if (move && !sameFs) src.delete(item)
        }
        skipped
    }

    private fun copyTree(src: FileSystem, item: FileItem, dst: FileSystem, target: String): Int {
        if (item.isDirectory && item.isSymlink) return 1
        var skipped = 0
        if (item.isDirectory) {
            dst.mkdir(target)
            src.list(item.path).forEach {
                checkName(it.name)
                skipped += copyTree(src, it, dst, joinPath(target, it.name))
            }
        } else {
            src.openRead(item.path).use { input ->
                dst.openWrite(target).use { output -> input.copyTo(output, 64 * 1024) }
            }
        }
        return skipped
    }

    /** A local file for viewers, archives and other apps: the file itself, or a copy in the cache. */
    suspend fun materialize(item: FileItem): File = io {
        if (item.storageType == StorageType.LOCAL && File(item.path).canRead()) return@io File(item.path)
        val dir = File(openCache, (item.storageType.name + item.serverId + item.path).hashCode().toUInt().toString())
        dir.mkdirs()
        checkName(item.name)
        val out = File(dir, item.name)
        val src = fs(item)
        if (src is QshFileSystem && !item.isDirectory) {
            src.download(item, dir.absolutePath)
        } else {
            src.openRead(item.path).use { input -> out.outputStream().use { input.copyTo(it, 64 * 1024) } }
        }
        out
    }

    /** MD5 and SHA-256 in one pass over the file. */
    suspend fun checksums(item: FileItem, progress: (Float) -> Unit = {}): Pair<String, String> = io {
        val md5 = MessageDigest.getInstance("MD5")
        val sha256 = MessageDigest.getInstance("SHA-256")
        fs(item).openRead(item.path).use { input ->
            val buffer = ByteArray(1024 * 1024)
            var done = 0L
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                md5.update(buffer, 0, n)
                sha256.update(buffer, 0, n)
                done += n
                if (item.size > 0) progress((done.toFloat() / item.size).coerceAtMost(1f))
            }
        }
        hex(md5.digest()) to hex(sha256.digest())
    }

    private fun hex(bytes: ByteArray): String {
        val digits = "0123456789abcdef"
        val out = CharArray(bytes.size * 2)
        bytes.forEachIndexed { i, b ->
            out[i * 2] = digits[(b.toInt() shr 4) and 0xf]
            out[i * 2 + 1] = digits[b.toInt() and 0xf]
        }
        return String(out)
    }

    /** Connects with a config that may not be saved yet; throws with the reason on failure. */
    suspend fun testConnection(config: ServerConfig) = io {
        create(config).use { f ->
            val start = config.remotePath.ifEmpty {
                when (f) {
                    is SftpFileSystem -> f.home()
                    is QshFileSystem -> f.home()
                    else -> "/"
                }
            }
            f.list(start)
        }
        Unit
    }

    /** A one-time code for the next login to this qsh server (see [Qsh.CodeRequired]). */
    fun provideQshCode(server: ServerConfig, code: String) {
        (fs(server.type, server.id) as? QshFileSystem)?.pendingCode = code.trim()
    }

    suspend fun pairQsh(config: ServerConfig, code: String): String = io {
        invalidate(config.id)
        qsh.pair(config, code)
    }

    suspend fun getStorageStats(): List<StorageStats> = io {
        listOfNotNull(
            runCatching {
                val internal = Environment.getExternalStorageDirectory()
                val st = StatFs(internal.path)
                StorageStats(str(R.string.internal_storage), internal.absolutePath, st.totalBytes, st.availableBytes)
            }.getOrNull(),
            runCatching {
                val st = StatFs("/")
                StorageStats(str(R.string.system_partition_root), "/", st.totalBytes, st.availableBytes, isRoot = true)
            }.getOrNull()
        )
    }

    private fun isSafeName(name: String) =
        name.isNotBlank() && name != "." && name != ".." && '/' !in name && '\\' !in name && '\u0000' !in name

    private fun checkName(name: String) {
        if (!isSafeName(name)) throw IOException(str(R.string.invalid_name, name))
    }

    private fun uniqueName(name: String, taken: Set<String>): String {
        if (name !in taken) return name
        val dot = name.lastIndexOf('.').takeIf { it > 0 } ?: name.length
        val base = name.substring(0, dot)
        val ext = name.substring(dot)
        return generateSequence(1) { it + 1 }.map { "$base ($it)$ext" }.first { it !in taken }
    }

    companion object {
        private const val MAX_TEXT_SIZE = 8L * 1024 * 1024
    }
}
