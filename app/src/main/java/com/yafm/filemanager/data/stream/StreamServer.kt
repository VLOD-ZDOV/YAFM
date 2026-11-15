package com.yafm.filemanager.data.stream

import android.net.Uri
import android.util.Log
import android.webkit.MimeTypeMap
import com.yafm.filemanager.data.model.FileItem
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Plays files from servers without fetching them first: a small HTTP server on 127.0.0.1 hands players
 * (libVLC, other apps) the bytes they ask for, with Range requests for seeking, read straight from the
 * server. Each file gets an unguessable link, so other apps on the phone cannot read what was not handed out.
 */
class StreamServer(
    /** Writes [length] bytes of [item] from [offset] into [out]; runs on the request's thread. */
    private val serve: (item: FileItem, offset: Long, length: Long, out: OutputStream) -> Unit,
    /** The file's size when the listing did not have it. */
    private val sizeOf: (FileItem) -> Long?
) {
    private val random = SecureRandom()
    private val byToken = ConcurrentHashMap<String, FileItem>()
    private val tokenOf = ConcurrentHashMap<String, String>()
    private val pool = Executors.newCachedThreadPool { r -> Thread(r, "stream").apply { isDaemon = true } }

    @Volatile
    private var socket: ServerSocket? = null

    /** An http:// link to [item], valid while the app lives. */
    @Synchronized
    fun urlFor(item: FileItem): Uri {
        val server = socket ?: start()
        val key = "${item.storageType}\u0000${item.serverId}\u0000${item.path}"
        val token = tokenOf.getOrPut(key) {
            val bytes = ByteArray(16).also { random.nextBytes(it) }
            bytes.joinToString("") { "%02x".format(it) }
        }
        byToken[token] = item
        return Uri.Builder()
            .scheme("http")
            .encodedAuthority("127.0.0.1:${server.localPort}")
            .appendPath(token)
            // The name lets players guess the format and show a title.
            .appendPath(item.name)
            .build()
    }

    private fun start(): ServerSocket {
        val server = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        socket = server
        Thread({
            while (!server.isClosed) {
                val client = try { server.accept() } catch (_: IOException) { break }
                pool.execute { handle(client) }
            }
        }, "stream-accept").apply { isDaemon = true }.start()
        return server
    }

    private fun handle(client: Socket): Unit = client.use { s ->
        try {
            s.soTimeout = 60_000
            val input = BufferedInputStream(s.getInputStream())
            val requestLine = readLine(input) ?: return
            val headers = HashMap<String, String>()
            while (true) {
                val line = readLine(input) ?: return
                if (line.isEmpty()) break
                val colon = line.indexOf(':')
                if (colon > 0) headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
            }
            val parts = requestLine.split(' ')
            val out = s.getOutputStream()
            if (parts.size < 2 || (parts[0] != "GET" && parts[0] != "HEAD")) return status(out, "405 Method Not Allowed")
            val token = parts[1].trimStart('/').substringBefore('/')
            val item = byToken[token] ?: return status(out, "404 Not Found")
            val size = item.size.takeIf { it > 0 } ?: sizeOf(item) ?: return status(out, "500 Internal Server Error")

            val range = headers["range"]?.let { parseRange(it, size) }
            if (headers["range"] != null && range == null) {
                return status(out, "416 Range Not Satisfiable", "Content-Range: bytes */$size\r\n")
            }
            val start = range?.first ?: 0L
            val end = range?.last ?: (size - 1)
            val length = end - start + 1
            val type = MimeTypeMap.getSingleton().getMimeTypeFromExtension(item.name.substringAfterLast('.', "").lowercase())
                ?: "application/octet-stream"
            val head = buildString {
                append(if (range != null) "HTTP/1.1 206 Partial Content\r\n" else "HTTP/1.1 200 OK\r\n")
                append("Content-Type: $type\r\n")
                append("Accept-Ranges: bytes\r\n")
                append("Content-Length: $length\r\n")
                if (range != null) append("Content-Range: bytes $start-$end/$size\r\n")
                append("Connection: close\r\n\r\n")
            }
            out.write(head.toByteArray(Charsets.US_ASCII))
            if (parts[0] == "HEAD" || length <= 0) return out.flush()
            serve(item, start, length, out)
            out.flush()
        } catch (_: SocketException) {
            // The player closed the connection, as it does on every seek.
        } catch (e: Exception) {
            Log.w("StreamServer", "stream failed", e)
        }
    }

    private fun status(out: OutputStream, status: String, extra: String = "") {
        out.write("HTTP/1.1 $status\r\n${extra}Content-Length: 0\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
        out.flush()
    }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val c = input.read()
            if (c < 0) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) return sb.toString().trimEnd('\r')
            if (sb.length > 8192) throw IOException("header line too long")
            sb.append(c.toChar())
        }
    }

    companion object {
        /** "bytes=a-b", "bytes=a-" or "bytes=-n" (the last n bytes); null when it does not fit the file. */
        fun parseRange(header: String, size: Long): LongRange? {
            val spec = header.trim().removePrefix("bytes=").substringBefore(',').trim()
            val dash = spec.indexOf('-')
            if (!header.trim().startsWith("bytes=") || dash < 0 || size <= 0) return null
            val a = spec.substring(0, dash).trim()
            val b = spec.substring(dash + 1).trim()
            return if (a.isEmpty()) {
                val n = b.toLongOrNull()?.takeIf { it > 0 } ?: return null
                maxOf(0L, size - n) until size
            } else {
                val start = a.toLongOrNull() ?: return null
                val end = if (b.isEmpty()) size - 1 else (b.toLongOrNull() ?: return null).coerceAtMost(size - 1)
                if (start >= size || end < start) null else start..end
            }
        }
    }
}
