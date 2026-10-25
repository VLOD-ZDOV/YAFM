package com.yafm.filemanager.data.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64
import java.util.zip.CRC32

class MetadataTest {

    private val secret = "GPS 55.7558 37.6173 SECRET"

    // Tiny real pictures (8 x 6) without metadata: after stripping, a dirty copy must equal them.
    private val plainJpeg = Base64.getDecoder().decode(
        "/9j/4AAQSkZJRgABAQAAAQABAAD/2wBDAAgGBgcGBQgHBwcJCQgKDBQNDAsLDBkSEw8UHRofHh0aHBwgJC4nICIsIxwcKDcpLDAxNDQ0Hyc5PTgyPC4zNDL/2wBDAQkJCQwLDBgNDRgyIRwhMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjL/wAARCAAGAAgDASIAAhEBAxEB/8QAHwAAAQUBAQEBAQEAAAAAAAAAAAECAwQFBgcICQoL/8QAtRAAAgEDAwIEAwUFBAQAAAF9AQIDAAQRBRIhMUEGE1FhByJxFDKBkaEII0KxwRVS0fAkM2JyggkKFhcYGRolJicoKSo0NTY3ODk6Q0RFRkdISUpTVFVWV1hZWmNkZWZnaGlqc3R1dnd4eXqDhIWGh4iJipKTlJWWl5iZmqKjpKWmp6ipqrKztLW2t7i5usLDxMXGx8jJytLT1NXW19jZ2uHi4+Tl5ufo6erx8vP09fb3+Pn6/8QAHwEAAwEBAQEBAQEBAQAAAAAAAAECAwQFBgcICQoL/8QAtREAAgECBAQDBAcFBAQAAQJ3AAECAxEEBSExBhJBUQdhcRMiMoEIFEKRobHBCSMzUvAVYnLRChYkNOEl8RcYGRomJygpKjU2Nzg5OkNERUZHSElKU1RVVldYWVpjZGVmZ2hpanN0dXZ3eHl6goOEhYaHiImKkpOUlZaXmJmaoqOkpaanqKmqsrO0tba3uLm6wsPExcbHyMnK0tPU1dbX2Nna4uPk5ebn6Onq8vP09fb3+Pn6/9oADAMBAAIRAxEAPwDnaKKKk/RD/9k="
    )
    private val plainPng = Base64.getDecoder().decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAgAAAAGCAIAAABxZ0isAAAAFElEQVR4nGM8oRHFgA0wYRWlkwQAw1ABVp3ROo4AAAAASUVORK5CYII="
    )

    private fun ByteArray.has(text: String) = String(this, Charsets.ISO_8859_1).contains(text)

    private fun segment(marker: Int, payload: ByteArray): ByteArray {
        val len = payload.size + 2
        return byteArrayOf(0xFF.toByte(), marker.toByte(), (len shr 8).toByte(), len.toByte()) + payload
    }

    @Test
    fun jpegLosesExifXmpCommentsAndTrailer() {
        val plain = plainJpeg
        val dirty = plain.copyOfRange(0, 2) +
            segment(0xE1, "Exif\u0000\u0000$secret".toByteArray()) +
            segment(0xE1, "http://ns.adobe.com/xap/1.0/\u0000$secret".toByteArray()) +
            segment(0xED, "Photoshop 3.0\u0000$secret".toByteArray()) +
            segment(0xFE, secret.toByteArray()) +
            plain.copyOfRange(2, plain.size) +
            "motion video $secret".toByteArray()
        assertTrue(dirty.has("SECRET"))

        val clean = Metadata.stripBytes(dirty)

        assertFalse(clean.has("SECRET"))
        assertTrue("the picture itself is untouched", clean.contentEquals(plain))
    }

    @Test
    fun pngLosesTextChunks() {
        val plain = plainPng
        val type = "tEXt".toByteArray()
        val data = "Comment\u0000$secret".toByteArray()
        val crc = CRC32().apply { update(type); update(data) }.value
        val chunk = byteArrayOf(0, 0, (data.size shr 8).toByte(), data.size.toByte()) + type + data +
            byteArrayOf((crc shr 24).toByte(), (crc shr 16).toByte(), (crc shr 8).toByte(), crc.toByte())
        val dirty = plain.copyOfRange(0, 33) + chunk + plain.copyOfRange(33, plain.size)

        val clean = Metadata.stripBytes(dirty)

        assertFalse(clean.has("SECRET"))
        assertTrue(clean.contentEquals(plain))
    }

    @Test
    fun webpLosesExifAndXmpChunks() {
        fun chunk(fourcc: String, payload: ByteArray): ByteArray {
            val n = payload.size
            val pad = if (n % 2 == 1) byteArrayOf(0) else byteArrayOf()
            return fourcc.toByteArray() + byteArrayOf(n.toByte(), (n shr 8).toByte(), (n shr 16).toByte(), (n shr 24).toByte()) + payload + pad
        }
        val vp8x = chunk("VP8X", byteArrayOf(0x0C, 0, 0, 0, 39, 0, 0, 29, 0, 0))
        val body = vp8x + chunk("VP8L", ByteArray(21) { 1 }) + chunk("EXIF", secret.toByteArray()) + chunk("XMP ", secret.toByteArray())
        val size = body.size + 4
        val dirty = "RIFF".toByteArray() + byteArrayOf(size.toByte(), (size shr 8).toByte(), 0, 0) + "WEBP".toByteArray() + body

        val clean = Metadata.stripBytes(dirty)

        assertFalse(clean.has("SECRET"))
        assertEquals("flags cleared", 0, clean[20].toInt())
        val riff = (clean[4].toInt() and 0xFF) or ((clean[5].toInt() and 0xFF) shl 8)
        assertEquals(clean.size - 8, riff)
    }
}
