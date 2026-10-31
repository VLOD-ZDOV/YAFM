package com.yafm.filemanager.data.repository

import com.yafm.filemanager.str
import com.yafm.filemanager.R
import android.content.Context
import android.os.Environment
import android.os.StatFs
import com.yafm.filemanager.data.archive.ArchiveManager
import com.yafm.filemanager.data.archive.RemoteArchive
import com.yafm.filemanager.data.fs.ExecResult
import com.yafm.filemanager.data.fs.FileSystem
import com.yafm.filemanager.data.fs.FtpFileSystem
import com.yafm.filemanager.data.fs.LocalFileSystem
import com.yafm.filemanager.data.fs.LoginPrompts
import com.yafm.filemanager.data.fs.PRIVATE_MODE
import com.yafm.filemanager.data.fs.QshFileSystem
import com.yafm.filemanager.data.fs.RootFileSystem
import com.yafm.filemanager.data.fs.SftpFileSystem
import com.yafm.filemanager.data.fs.SmbFileSystem
import com.yafm.filemanager.data.fs.checkName
import com.yafm.filemanager.data.fs.isSafeName
import com.yafm.filemanager.data.fs.joinPath
import com.yafm.filemanager.data.fs.parentPath
import com.yafm.filemanager.data.fs.shellQuote
import com.yafm.filemanager.data.fs.uniqueName
import com.yafm.filemanager.data.media.MediaIndex
import com.yafm.filemanager.data.model.FileItem
import com.yafm.filemanager.data.model.Location
import com.yafm.filemanager.data.model.ServerConfig
import com.yafm.filemanager.data.model.StorageStats
import com.yafm.filemanager.data.model.StorageType
import com.yafm.filemanager.data.qsh.Qsh
import com.yafm.filemanager.data.transfer.Copier
import com.yafm.filemanager.data.transfer.Runner
import com.yafm.filemanager.data.transfer.TransferMeter
import com.yafm.filemanager.data.transfer.TransferProgress
import com.yafm.filemanager.data.transfer.TransferQueue
import com.yafm.filemanager.data.transfer.TransferRequest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.runInterruptible
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Single entry point to all storages. Keeps one open connection per server until it is edited or deleted,
 * or the app has been left for [KEEP_MINUTES] (see [com.yafm.filemanager.data.fs.ConnectionService]).
 */
class FileRepository(context: Context) {
    private val appContext = context.applicationContext

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

    /** A login waits for what the user types (a one-time code); a null answer gives up. */
    class CodeRequest(val server: ServerConfig, val prompt: String, val answer: CompletableDeferred<String?>)

    /** A server key seen for the first time, or a different one than before ([changed]). */
    class HostKeyRequest(val server: ServerConfig, val fingerprint: String, val changed: Boolean, val answer: CompletableDeferred<Boolean>)

    /** What the server info screen shows; null where the server does not tell. */
    data class ServerInfo(val fingerprint: String?, val system: String?, val totalBytes: Long?, val freeBytes: Long?)

    private val _codeRequest = MutableStateFlow<CodeRequest?>(null)
    val codeRequest: StateFlow<CodeRequest?> = _codeRequest.asStateFlow()

    private val _hostKeyRequest = MutableStateFlow<HostKeyRequest?>(null)
    val hostKeyRequest: StateFlow<HostKeyRequest?> = _hostKeyRequest.asStateFlow()

    private val codes = ConcurrentHashMap<String, String>()
    private val asking = Mutex()
    @Volatile private var codesGiven = 0
    private val running = AtomicInteger()

    val transfers = TransferQueue(this, ::describeError)

    /** A file operation is under way, which must not lose its connection. */
    val isBusy: Boolean get() = running.get() > 0 || transfers.isActive

    fun answerCode(code: String?) {
        _codeRequest.value?.answer?.complete(code)
    }

    fun answerHostKey(trust: Boolean) {
        _hostKeyRequest.value?.answer?.complete(trust)
    }

    private val prompts = object : LoginPrompts {
        override fun trustHostKey(server: ServerConfig, fingerprint: String, changed: Boolean): Boolean = runBlocking {
            val request = HostKeyRequest(server, fingerprint, changed, CompletableDeferred())
            _hostKeyRequest.value = request
            try {
                request.answer.await()
            } finally {
                _hostKeyRequest.value = null
            }
        }

        override fun answer(server: ServerConfig, prompt: String): String? = runBlocking {
            asking.withLock { typed(server, prompt) }
        }
    }

    // Every operation goes through here, so whichever one meets a login that needs a code can ask for it.
    // Interruptible: cancelling a transfer must not wait for a stuck network read.
    private suspend fun <T> io(block: () -> T): T {
        while (true) {
            val seen = codesGiven
            try {
                return runInterruptible(Dispatchers.IO) {
                    running.incrementAndGet()
                    try { block() } finally { running.decrementAndGet() }
                }
            } catch (e: Qsh.CodeRequired) {
                if (!askCode(e, seen)) throw e
            }
        }
    }

    private val runner = object : Runner {
        override suspend fun <T> run(block: () -> T): T = io(block)
    }

    /** False when the user gave up. [seen]: a code given since then, for a parallel operation, is used without asking. */
    private suspend fun askCode(e: Qsh.CodeRequired, seen: Int): Boolean = asking.withLock {
        if (codesGiven != seen) return true
        val code = typed(e.server, e.prompt) ?: return false
        codes[e.server.id] = code
        codesGiven++
        true
    }

    private suspend fun typed(server: ServerConfig, prompt: String): String? {
        val request = CodeRequest(server, prompt, CompletableDeferred())
        _codeRequest.value = request
        return try {
            request.answer.await()?.trim()
        } finally {
            _codeRequest.value = null
        }
    }

    private fun create(config: ServerConfig): FileSystem = when (config.type) {
        StorageType.SMB -> SmbFileSystem(config)
        StorageType.SFTP -> SftpFileSystem(config, knownHosts, prompts)
        StorageType.FTP -> FtpFileSystem(config)
        StorageType.QSH -> QshFileSystem(config, qsh) { codes.remove(config.id) }
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
        remote.remove(serverId)?.let { fs ->
            Thread { runCatching { if (fs is QshFileSystem) fs.logOut() else fs.close() } }.start()
        }
    }

    val hasOpenConnections: Boolean get() = remote.values.any { it.isOpen }

    fun closeConnections() = remote.keys.toList().forEach { invalidate(it) }

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

    suspend fun readText(item: FileItem): String = readBytes(item).toString(Charsets.UTF_8)

    /** The whole file, for the editor; files over [MAX_TEXT_SIZE] are refused. */
    suspend fun readBytes(item: FileItem): ByteArray = io {
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
        out.toByteArray()
    }

    suspend fun writeBytes(item: FileItem, data: ByteArray) = io {
        val f = fs(item)
        if (!(f.isRemote && !item.isSymlink && writeAside(f, item, data))) f.openWrite(item.path).use { it.write(data) }
    }

    /**
     * Saves through a temporary file, so a connection lost halfway does not leave half a file on the
     * server. False if that cannot be done (a folder the user may not write in): then only the file is left.
     */
    private fun writeAside(f: FileSystem, item: FileItem, data: ByteArray): Boolean {
        if (f.stat(item.path) == null) return false
        val temp = joinPath(parentPath(item.path), ".${item.name.take(100)}.yafm-part")
        val written = runCatching {
            f.openWrite(temp).use {
                runCatching { f.chmod(temp, PRIVATE_MODE) }
                it.write(data)
            }
        }
        if (written.isFailure) {
            runCatching { f.removeFile(temp) }
            return false
        }
        f.replaceKeeping(temp, item.path)
        return true
    }

    suspend fun writeText(item: FileItem, text: String) = writeBytes(item, text.toByteArray(Charsets.UTF_8))

    suspend fun delete(item: FileItem) = io { fs(item).delete(item) }

    suspend fun rename(item: FileItem, newName: String) = io {
        checkName(newName)
        val f = fs(item)
        // SFTP's rename takes over a name that is in use, and what had it is gone.
        requireFree(f, parentPath(item.path), newName)
        f.rename(item.path, joinPath(parentPath(item.path), newName))
    }

    suspend fun createDirectory(location: Location, name: String) = io {
        checkName(name)
        fs(location).mkdir(joinPath(location.path, name))
    }

    /** A link named [name] in [location] to [target], which is in the same storage. */
    suspend fun createLink(target: FileItem, location: Location, name: String) = io {
        checkName(name)
        fs(location).symlink(target.path, joinPath(location.path, name))
    }

    suspend fun createFile(location: Location, name: String) = io {
        checkName(name)
        val f = fs(location)
        // Most servers create a file by opening it for writing, which empties one that exists.
        requireFree(f, location.path, name)
        f.createFile(joinPath(location.path, name))
    }

    private fun requireFree(f: FileSystem, dir: String, name: String) {
        if (f.isRemote && f.list(dir).any { it.name == name }) throw IOException(str(R.string.already_exists, name))
    }

    /** [recursive]: also everything inside; folders then stay enterable wherever they stay readable. */
    suspend fun changePermissions(item: FileItem, octal: String, recursive: Boolean = false) = io {
        val mode = octal.takeIf { it.matches(Regex("[0-7]{3,4}")) }?.toInt(8)
            ?: throw IOException(str(R.string.invalid_permissions, octal))
        val f = fs(item)
        fun apply(target: FileItem) {
            if (target.isSymlink) return
            if (!target.isDirectory) return f.chmod(target.path, mode)
            f.chmod(target.path, mode or ((mode and 0b100100100) shr 2))
            f.list(target.path).forEach(::apply)
        }
        if (recursive && item.isDirectory) apply(item) else f.chmod(item.path, mode)
    }

    /** Which of [items] would meet a name already taken in [dest]. */
    suspend fun conflicts(items: List<FileItem>, dest: Location, move: Boolean): List<FileItem> = io {
        val dst = fs(dest)
        val taken = dst.list(dest.path).mapTo(HashSet()) { it.name }
        items.filter { item ->
            val itself = fs(item) === dst && joinPath(dest.path, item.name) == item.path
            item.name in taken && !itself
        }
    }

    /**
     * Copies or moves into the target, across storages if needed ([Copier]). Links to directories are not
     * followed (a link to `.` would never end); returns how many were skipped.
     */
    suspend fun transfer(request: TransferRequest, progress: (TransferProgress) -> Unit): Int {
        val dest = request.dest
        val dst = io { fs(dest) }
        val sizes = HashMap<String, Long>()
        var files = 0
        // Walked first, for the percentage and the time left.
        io {
            fun measure(src: FileSystem, item: FileItem): Long {
                if (item.isDirectory && item.isSymlink) return 0
                if (!item.isDirectory) { files++; return item.size }
                return src.list(item.path).sumOf { measure(src, it) }
            }
            for (item in request.items) {
                val src = fs(item)
                val renamed = request.move && src === dst
                if (renamed) files++
                sizes[item.path] = if (renamed) item.size else measure(src, item)
            }
        }
        val taken = io { dst.list(dest.path).associateBy { it.name } }.toMutableMap()
        val meter = TransferMeter(files, sizes.values.sum(), report = progress)
        val copier = Copier(dst, request.onConflict, meter, runner)
        try {
            for (item in request.items) {
                copier.place(io { fs(item) }, item, dest.path, request.move, taken, sizes[item.path] ?: 0L)
            }
        } finally {
            // New files are not in the gallery and the categories until Android indexes them.
            if (dest.type == StorageType.LOCAL && copier.written.isNotEmpty()) {
                android.media.MediaScannerConnection.scanFile(appContext, copier.written.takeLast(MAX_SCANNED).toTypedArray(), null, null)
            }
        }
        return copier.skippedLinks
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
        // Files the app can read itself (also root-mode ones in shared storage) need no copy.
        val direct = item.storageType == StorageType.LOCAL || item.storageType == StorageType.ROOT
        if (direct && File(item.path).canRead()) return@io File(item.path)
        val dir = File(openCache, (item.storageType.name + item.serverId + item.path).hashCode().toUInt().toString())
        dir.mkdirs()
        checkName(item.name)
        val out = File(dir, item.name)
        out.deleteRecursively()
        copyTree(fs(item), item, local, out.path)
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

    fun canRunOnServer(location: Location) = location.type == StorageType.SFTP || location.type == StorageType.QSH

    private fun FileSystem.run(command: String, timeoutMinutes: Long = 2): ExecResult {
        val result = exec(command, timeoutMinutes) ?: throw UnsupportedOperationException(str(R.string.not_supported_on_this_server))
        if (result.code != 0) throw IOException(result.errors.trim().ifEmpty { str(R.string.server_command_failed, result.code) })
        return result
    }

    /** Packs [items] of [location] on the server, with its programs; the archive stays beside them. Returns its name. */
    suspend fun compressOnServer(items: List<FileItem>, location: Location, name: String, format: ArchiveManager.Format): String = io {
        checkName(name)
        val f = fs(location)
        val archive = uniqueName(name, f.list(location.path).mapTo(HashSet()) { it.name })
        f.run(RemoteArchive.compress(format, location.path, items.map { it.name }, joinPath(location.path, archive)), timeoutMinutes = 24 * 60)
        archive
    }

    /** Unpacks on the server into a new folder beside the archive. Returns its name. */
    suspend fun extractOnServer(item: FileItem): String = io {
        val f = fs(item)
        val dir = parentPath(item.path)
        val folder = uniqueName(RemoteArchive.folderName(item.name), f.list(dir).mapTo(HashSet()) { it.name })
        val command = RemoteArchive.extract(item.path, joinPath(dir, folder))
            ?: throw IOException(str(R.string.unknown_archive_format))
        f.run(command, timeoutMinutes = 24 * 60)
        folder
    }

    /** A folder's size: asked of the server where it can tell, else added up from the listings. */
    suspend fun folderSize(item: FileItem): Long = io {
        val f = fs(item)
        fun walk(target: FileItem): Long = when {
            target.isDirectory && target.isSymlink -> 0
            target.isDirectory -> f.list(target.path).sumOf { walk(it) }
            else -> target.size
        }
        runCatching { f.exec("du -sk -- ${shellQuote(item.path)}", 10) }.getOrNull()
            ?.takeIf { it.code == 0 }?.output?.trim()?.substringBefore('\t')?.toLongOrNull()?.let { it * 1024 }
            ?: walk(item)
    }

    suspend fun serverInfo(server: ServerConfig, path: String): ServerInfo = io {
        val f = fs(server.type, server.id)
        val space = runCatching { f.exec("df -Pk -- ${shellQuote(path)}") }.getOrNull()
            ?.takeIf { it.code == 0 }?.output?.lines()?.getOrNull(1)?.trim()?.split(Regex("\\s+"))
        ServerInfo(
            fingerprint = (f as? SftpFileSystem)?.fingerprint(),
            system = runCatching { f.exec("uname -srm") }.getOrNull()?.takeIf { it.code == 0 }?.output?.trim()?.ifEmpty { null },
            totalBytes = space?.getOrNull(1)?.toLongOrNull()?.times(1024),
            freeBytes = space?.getOrNull(3)?.toLongOrNull()?.times(1024)
        )
    }

    /** Where a link points. */
    suspend fun linkTarget(item: FileItem): String? = io { runCatching { fs(item).readLink(item.path) }.getOrNull() }

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

    /** Copies a private key the user picked into app storage, readable by the app alone; returns its path. */
    suspend fun importKey(uri: android.net.Uri, serverId: String): String = io {
        val text = appContext.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
            ?: throw IOException(str(R.string.not_a_private_key))
        if (!text.contains("PRIVATE KEY-----")) throw IOException(str(R.string.not_a_private_key))
        val dir = File(appContext.filesDir, "keys").apply { mkdirs() }
        val file = File(dir, "$serverId.key")
        file.writeText(text)
        // qsh and OpenSSH refuse key files others can read.
        android.system.Os.chmod(file.path, "600".toInt(8))
        file.absolutePath
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

    companion object {
        /** How long connections last once the app is left. */
        const val KEEP_MINUTES = 30L

        private const val MAX_SCANNED = 5000
        private const val MAX_TEXT_SIZE = 8L * 1024 * 1024
    }
}
