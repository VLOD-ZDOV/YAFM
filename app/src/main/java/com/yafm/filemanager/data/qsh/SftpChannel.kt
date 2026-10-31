package com.yafm.filemanager.data.qsh

import com.yafm.filemanager.str
import com.yafm.filemanager.R
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.util.ArrayDeque
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.atomic.AtomicInteger

/**
 * Minimal SFTP v3 client over any byte stream (here: `qsh -s sftp`). Requests may be issued from several
 * threads at once; a reader thread hands each reply to its request by id, so reads and writes are pipelined.
 */
class SftpChannel(input: InputStream, output: OutputStream) : Closeable {

    class Attrs(val size: Long?, val uid: Int?, val gid: Int?, val permissions: Int?, val mtime: Long?) {
        val isDirectory get() = permissions != null && permissions and S_IFMT == S_IFDIR
        val isLink get() = permissions != null && permissions and S_IFMT == S_IFLNK
    }

    class Entry(val name: String, val attrs: Attrs)

    class SftpException(val code: Int, message: String) : IOException(message)

    private class Packet(val type: Int, val buf: ByteBuffer)

    private val inp = DataInputStream(BufferedInputStream(input, 64 * 1024))
    private val out = DataOutputStream(BufferedOutputStream(output, 64 * 1024))
    private val pending = ConcurrentHashMap<Int, CompletableFuture<Packet>>()
    private val nextId = AtomicInteger(1)
    @Volatile private var failure: IOException? = null
    private val extensions = HashSet<String>()

    val isAlive: Boolean get() = failure == null

    init {
        synchronized(out) {
            out.writeInt(5)
            out.writeByte(FXP_INIT)
            out.writeInt(3)
            out.flush()
        }
        val len = inp.readInt()
        if (len < 5 || len > MAX_PACKET) throw IOException(str(R.string.the_server_does_not_speak_sftp))
        val hello = ByteArray(len).also { inp.readFully(it) }
        val reply = ByteBuffer.wrap(hello)
        if (reply.get().toInt() != FXP_VERSION) throw IOException(str(R.string.the_server_does_not_speak_sftp))
        reply.getInt()
        while (reply.hasRemaining()) {
            extensions += reply.string()
            reply.string()
        }
        Thread(::readLoop, "sftp-reader").apply { isDaemon = true }.start()
    }

    private fun readLoop() {
        try {
            while (true) {
                val len = inp.readInt()
                if (len < 5 || len > MAX_PACKET) throw IOException(str(R.string.invalid_sftp_packet))
                val body = ByteArray(len)
                inp.readFully(body)
                val buf = ByteBuffer.wrap(body)
                val type = buf.get().toInt() and 0xff
                val id = buf.getInt()
                pending.remove(id)?.complete(Packet(type, buf))
            }
        } catch (e: IOException) {
            fail(e)
        }
    }

    private fun fail(e: IOException) {
        if (failure == null) failure = if (e is java.io.EOFException) IOException(str(R.string.connection_closed)) else e
        pending.values.forEach { it.completeExceptionally(failure) }
        pending.clear()
    }

    private fun send(type: Int, payload: Payload.() -> Unit): CompletableFuture<Packet> {
        failure?.let { throw it }
        val id = nextId.getAndIncrement()
        val future = CompletableFuture<Packet>()
        pending[id] = future
        val body = Payload().apply(payload).bytes()
        try {
            synchronized(out) {
                out.writeInt(body.size + 5)
                out.writeByte(type)
                out.writeInt(id)
                out.write(body)
                out.flush()
            }
        } catch (e: IOException) {
            fail(e)
        }
        // The reader may have failed between the check above and registering this request.
        failure?.let { future.completeExceptionally(it) }
        return future
    }

    private fun CompletableFuture<Packet>.await(): Packet = try {
        get()
    } catch (e: ExecutionException) {
        throw e.cause as? IOException ?: IOException(e.cause)
    }

    private fun call(type: Int, payload: Payload.() -> Unit) = send(type, payload).await()

    private fun Packet.expect(type: Int): Packet {
        if (this.type == FXP_STATUS) checkStatus()
        if (this.type != type) throw IOException(str(R.string.unexpected_sftp_reply_2, type, this.type))
        return this
    }

    /** Throws for any status but OK; returns false for EOF. */
    private fun Packet.checkStatus(): Boolean {
        val code = buf.getInt()
        if (code == FX_OK) return true
        if (code == FX_EOF) return false
        val msg = runCatching { buf.string() }.getOrDefault("")
        throw SftpException(code, statusText(code, msg))
    }

    private fun okStatus(type: Int, payload: Payload.() -> Unit) {
        val p = call(type, payload)
        if (p.type != FXP_STATUS) throw IOException(str(R.string.unexpected_sftp_reply))
        p.checkStatus()
    }

    fun realpath(path: String): String {
        val p = call(FXP_REALPATH) { string(path) }.expect(FXP_NAME)
        p.buf.getInt()
        return p.buf.string()
    }

    fun stat(path: String): Attrs = call(FXP_STAT) { string(path) }.expect(FXP_ATTRS).buf.attrs()

    fun lstat(path: String): Attrs = call(FXP_LSTAT) { string(path) }.expect(FXP_ATTRS).buf.attrs()

    fun readlink(path: String): String {
        val p = call(FXP_READLINK) { string(path) }.expect(FXP_NAME)
        p.buf.getInt()
        return p.buf.string()
    }

    fun list(dir: String): List<Entry> {
        val handle = call(FXP_OPENDIR) { string(dir) }.expect(FXP_HANDLE).buf.bytes()
        try {
            val result = mutableListOf<Entry>()
            while (true) {
                val p = call(FXP_READDIR) { bytes(handle) }
                if (p.type == FXP_STATUS) {
                    if (!p.checkStatus()) break else continue
                }
                p.expect(FXP_NAME)
                val count = p.buf.getInt()
                if (count < 0 || result.size + count > MAX_ENTRIES) throw IOException(str(R.string.too_many_files_in_the_folder))
                repeat(count) {
                    val name = p.buf.string()
                    p.buf.string() // longname
                    val attrs = p.buf.attrs()
                    if (name != "." && name != "..") result += Entry(name, attrs)
                }
            }
            return result
        } finally {
            closeHandle(handle)
        }
    }

    fun mkdir(path: String) = okStatus(FXP_MKDIR) { string(path); int(0) }
    fun rmdir(path: String) = okStatus(FXP_RMDIR) { string(path) }
    fun remove(path: String) = okStatus(FXP_REMOVE) { string(path) }
    fun rename(from: String, to: String) = okStatus(FXP_RENAME) { string(from); string(to) }
    fun chmod(path: String, mode: Int) = okStatus(FXP_SETSTAT) { string(path); int(ATTR_PERMISSIONS); int(mode) }

    fun chown(path: String, uid: Int, gid: Int) = okStatus(FXP_SETSTAT) { string(path); int(ATTR_UIDGID); int(uid); int(gid) }

    /** Takes the name over atomically where the server allows it; plain SFTP refuses a taken name. */
    fun renameOver(from: String, to: String) {
        if (POSIX_RENAME in extensions) okStatus(FXP_EXTENDED) { string(POSIX_RENAME); string(from); string(to) }
        else { runCatching { remove(to) }; rename(from, to) }
    }

    fun setMtime(path: String, seconds: Long) {
        val time = seconds.toInt()
        okStatus(FXP_SETSTAT) { string(path); int(ATTR_ACMODTIME); int(time); int(time) }
    }

    fun open(path: String, flags: Int): ByteArray =
        call(FXP_OPEN) { string(path); int(flags); int(0) }.expect(FXP_HANDLE).buf.bytes()

    fun closeHandle(handle: ByteArray) = okStatus(FXP_CLOSE) { bytes(handle) }

    fun openInputStream(path: String, offset: Long = 0): InputStream = ReadStream(open(path, FXF_READ), offset)

    fun openOutputStream(path: String, exclusive: Boolean = false): OutputStream =
        WriteStream(open(path, FXF_WRITE or FXF_CREAT or if (exclusive) FXF_EXCL else FXF_TRUNC), 0)

    /** Writes at an explicit offset: FXF_APPEND is not honoured by every server. */
    fun openAppendStream(path: String): OutputStream {
        val size = stat(path).size ?: 0L
        return WriteStream(open(path, FXF_WRITE), size)
    }

    private inner class ReadStream(private val handle: ByteArray, start: Long) : InputStream() {
        private val ahead = ArrayDeque<Pair<Long, CompletableFuture<Packet>>>()
        private var nextOffset = start
        private var chunk = ByteArray(0)
        private var pos = 0
        private var eof = false
        private var closed = false

        private fun fill(): Boolean {
            while (pos >= chunk.size) {
                if (eof) return false
                while (ahead.size < READ_AHEAD) {
                    val off = nextOffset
                    ahead.add(off to send(FXP_READ) { bytes(handle); long(off); int(CHUNK) })
                    nextOffset += CHUNK
                }
                val (offset, future) = ahead.removeFirst()
                val p = future.await()
                if (p.type == FXP_STATUS) {
                    if (!p.checkStatus()) eof = true
                    continue
                }
                chunk = p.expect(FXP_DATA).buf.bytes()
                pos = 0
                if (chunk.size < CHUNK) {
                    // Short read: the requests already queued are for the wrong offsets.
                    ahead.clear()
                    nextOffset = offset + chunk.size
                }
            }
            return true
        }

        override fun read(): Int = if (fill()) chunk[pos++].toInt() and 0xff else -1

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            if (!fill()) return -1
            val n = minOf(len, chunk.size - pos)
            System.arraycopy(chunk, pos, b, off, n)
            pos += n
            return n
        }

        override fun close() {
            if (closed) return
            closed = true
            // Replies to pending reads still arrive; their futures are simply dropped.
            ahead.clear()
            closeHandle(handle)
        }
    }

    private inner class WriteStream(private val handle: ByteArray, start: Long) : OutputStream() {
        private val buffer = ByteArray(CHUNK)
        private var used = 0
        private var offset = start
        private val inFlight = ArrayDeque<CompletableFuture<Packet>>()
        private var closed = false

        override fun write(b: Int) {
            if (used == buffer.size) sendChunk()
            buffer[used++] = b.toByte()
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            var o = off
            var left = len
            while (left > 0) {
                if (used == buffer.size) sendChunk()
                val n = minOf(left, buffer.size - used)
                System.arraycopy(b, o, buffer, used, n)
                used += n
                o += n
                left -= n
            }
        }

        private fun sendChunk() {
            if (used == 0) return
            val data = buffer.copyOf(used)
            val off = offset
            inFlight.add(send(FXP_WRITE) { bytes(handle); long(off); bytes(data) })
            offset += used
            used = 0
            while (inFlight.size > WRITE_AHEAD) inFlight.removeFirst().await().expect(FXP_STATUS)
        }

        override fun flush() {
            sendChunk()
            while (inFlight.isNotEmpty()) inFlight.removeFirst().await().also { if (it.type == FXP_STATUS) it.checkStatus() }
        }

        override fun close() {
            if (closed) return
            closed = true
            try {
                flush()
            } finally {
                closeHandle(handle)
            }
        }
    }

    override fun close() {
        runCatching { out.close() }
        runCatching { inp.close() }
        fail(IOException(str(R.string.connection_closed)))
    }

    private class Payload {
        private val bos = ByteArrayOutputStream()
        private val d = DataOutputStream(bos)
        fun int(v: Int) = d.writeInt(v)
        fun long(v: Long) = d.writeLong(v)
        fun bytes(v: ByteArray) { d.writeInt(v.size); d.write(v) }
        fun string(v: String) = bytes(v.toByteArray(Charsets.UTF_8))
        fun bytes(): ByteArray = bos.toByteArray()
    }

    companion object {
        private const val FXP_INIT = 1
        private const val FXP_VERSION = 2
        private const val FXP_OPEN = 3
        private const val FXP_CLOSE = 4
        private const val FXP_READ = 5
        private const val FXP_WRITE = 6
        private const val FXP_LSTAT = 7
        private const val FXP_SETSTAT = 9
        private const val FXP_OPENDIR = 11
        private const val FXP_READDIR = 12
        private const val FXP_REMOVE = 13
        private const val FXP_MKDIR = 14
        private const val FXP_RMDIR = 15
        private const val FXP_REALPATH = 16
        private const val FXP_STAT = 17
        private const val FXP_RENAME = 18
        private const val FXP_READLINK = 19
        private const val FXP_EXTENDED = 200
        private const val FXP_STATUS = 101
        private const val FXP_HANDLE = 102
        private const val FXP_DATA = 103
        private const val FXP_NAME = 104
        private const val FXP_ATTRS = 105

        private const val FXF_READ = 1
        private const val FXF_WRITE = 2
        private const val FXF_CREAT = 8
        private const val FXF_TRUNC = 0x10
        private const val FXF_EXCL = 0x20

        private const val ATTR_SIZE = 1
        private const val ATTR_UIDGID = 2
        private const val ATTR_PERMISSIONS = 4
        private const val ATTR_ACMODTIME = 8
        private const val ATTR_EXTENDED = Int.MIN_VALUE

        private const val FX_OK = 0
        private const val FX_EOF = 1

        const val FX_NO_SUCH_FILE = 2

        const val S_IFMT = 0xF000
        const val S_IFDIR = 0x4000
        const val S_IFLNK = 0xA000

        private const val CHUNK = 32 * 1024
        private const val POSIX_RENAME = "posix-rename@openssh.com"
        // 2 MB on the way: at 100 ms to the server that allows 20 MB/s.
        private const val READ_AHEAD = 64
        private const val WRITE_AHEAD = 64
        private const val MAX_PACKET = 256 * 1024 + 64
        // Bounds what a misbehaving server can make the app hold for one listing.
        private const val MAX_ENTRIES = 500_000

        private fun ByteBuffer.bytes(): ByteArray {
            val len = getInt()
            // Lengths come from the server: never trust them past the packet.
            if (len < 0 || len > remaining()) throw IOException(str(R.string.invalid_sftp_reply))
            return ByteArray(len).also { get(it) }
        }
        private fun ByteBuffer.string(): String = String(bytes(), Charsets.UTF_8)

        private fun ByteBuffer.attrs(): Attrs {
            val flags = getInt()
            val size = if (flags and ATTR_SIZE != 0) getLong() else null
            var uid: Int? = null
            var gid: Int? = null
            if (flags and ATTR_UIDGID != 0) { uid = getInt(); gid = getInt() }
            val perms = if (flags and ATTR_PERMISSIONS != 0) getInt() else null
            var mtime: Long? = null
            if (flags and ATTR_ACMODTIME != 0) { getInt(); mtime = getInt().toLong() and 0xffffffffL }
            if (flags and ATTR_EXTENDED != 0) {
                val count = getInt()
                if (count < 0 || count > remaining() / 8) throw IOException(str(R.string.invalid_sftp_reply))
                repeat(count) { bytes(); bytes() }
            }
            return Attrs(size, uid, gid, perms, mtime)
        }

        private fun statusText(code: Int, msg: String) = when (code) {
            2 -> str(R.string.file_not_found)
            3 -> str(R.string.permission_denied)
            else -> msg.ifBlank { str(R.string.sftp_error, code) }
        }
    }
}
