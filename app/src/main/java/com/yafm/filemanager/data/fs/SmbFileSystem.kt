package com.yafm.filemanager.data.fs

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.share.DiskShare
import com.yafm.filemanager.data.model.FileItem
import com.yafm.filemanager.data.model.ServerConfig
import com.yafm.filemanager.data.model.StorageType
import java.io.FilterInputStream
import java.io.FilterOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.EnumSet

/** SMB2/3 (smbj). Paths are share-relative with `/`; one connection per server. */
class SmbFileSystem(private val config: ServerConfig) : FileSystem {

    private val client = SMBClient()
    private var share: DiskShare? = null

    @Synchronized
    private fun share(): DiskShare {
        share?.takeIf { it.isConnected }?.let { return it }
        val connection = client.connect(config.host, config.port)
        val auth = if (config.isAnonymous || config.username.isEmpty()) {
            AuthenticationContext.anonymous()
        } else {
            AuthenticationContext(config.username, config.password.toCharArray(), "")
        }
        val s = connection.authenticate(auth).connectShare(config.shareName.ifEmpty { "public" }) as DiskShare
        share = s
        return s
    }

    private fun smbPath(path: String) = path.trim('/').replace('/', '\\')

    override fun list(dir: String): List<FileItem> =
        share().list(smbPath(dir))
            .filter { it.fileName != "." && it.fileName != ".." }
            .map { info ->
                val isDir = info.fileAttributes and FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value != 0L
                val isLink = info.fileAttributes and FileAttributes.FILE_ATTRIBUTE_REPARSE_POINT.value != 0L
                FileItem(
                    name = info.fileName,
                    path = joinPath(dir.ifEmpty { "/" }, info.fileName),
                    size = if (isDir) 0L else info.endOfFile,
                    isDirectory = isDir,
                    lastModified = info.lastWriteTime?.toEpochMillis() ?: 0L,
                    permissions = if (isDir) "drwxr-xr-x" else "-rw-r--r--",
                    storageType = StorageType.SMB,
                    serverId = config.id,
                    isSymlink = isLink
                )
            }

    override fun openRead(path: String): InputStream {
        val file = share().openFile(
            smbPath(path), EnumSet.of(AccessMask.GENERIC_READ), null,
            SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_OPEN, null
        )
        return object : FilterInputStream(file.inputStream) {
            override fun close() {
                try { super.close() } finally { file.close() }
            }
        }
    }

    override fun openWrite(path: String): OutputStream {
        val file = share().openFile(
            smbPath(path), EnumSet.of(AccessMask.GENERIC_WRITE), null,
            SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_OVERWRITE_IF, null
        )
        return object : FilterOutputStream(file.outputStream) {
            override fun write(b: ByteArray, off: Int, len: Int) = out.write(b, off, len)
            override fun close() {
                try { super.close() } finally { file.close() }
            }
        }
    }

    override fun mkdir(path: String) = share().mkdir(smbPath(path))

    override fun rename(from: String, to: String) {
        val s = share()
        val entry = if (s.folderExists(smbPath(from))) {
            s.openDirectory(smbPath(from), EnumSet.of(AccessMask.DELETE, AccessMask.GENERIC_READ), null,
                SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_OPEN, null)
        } else {
            s.openFile(smbPath(from), EnumSet.of(AccessMask.DELETE, AccessMask.GENERIC_READ), null,
                SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_OPEN, null)
        }
        entry.use { it.rename(smbPath(to)) }
    }

    override fun removeFile(path: String) = share().rm(smbPath(path))
    override fun removeDir(path: String) = share().rmdir(smbPath(path), false)

    override fun delete(item: FileItem) {
        // A recursive rmdir would walk into a linked directory: links are removed one by one instead.
        if (item.isDirectory && !item.isSymlink) super.delete(item) else if (item.isDirectory) removeDir(item.path) else removeFile(item.path)
    }

    @Synchronized
    override fun close() {
        runCatching { share?.close() }
        share = null
        runCatching { client.close() }
    }
}
