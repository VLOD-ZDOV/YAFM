package com.yafm.filemanager.data.media

import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException

/**
 * Removes what a photo says about itself: when and where it was taken, the camera, comments, edits.
 * The file is rewritten without its metadata blocks; the picture data is copied as is, so nothing is
 * re-encoded. Only the orientation is put back, or the picture would show turned.
 */
object Metadata {

    /** Formats whose details can be removed. */
    val SUPPORTED = setOf("jpg", "jpeg", "png", "webp")

    /**
     * A clean copy next to [file] ("photo_clean.jpg"); the original keeps its details, so the gallery
     * still knows when it was taken and a motion photo keeps its video.
     */
    fun cleanCopy(file: File): File {
        val orientation = runCatching {
            ExifInterface(file.absolutePath).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        val clean = stripBytes(file.readBytes())
        val base = file.nameWithoutExtension + "_clean"
        var copy = File(file.parentFile, "$base.${file.extension}")
        var n = 2
        while (copy.exists()) copy = File(file.parentFile, "${base}_${n++}.${file.extension}")
        copy.writeBytes(clean)
        if (orientation != ExifInterface.ORIENTATION_NORMAL) {
            ExifInterface(copy.absolutePath).apply {
                setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString())
                saveAttributes()
            }
        }
        return copy
    }

    /** The same picture without metadata; the format is told by its signature, not the name. */
    fun stripBytes(data: ByteArray): ByteArray = when {
        data.size > 3 && data[0] == 0xFF.toByte() && data[1] == 0xD8.toByte() -> stripJpeg(data)
        data.size > 8 && data.copyOfRange(0, 8).contentEquals(PNG_SIGNATURE) -> stripPng(data)
        data.size > 12 && String(data, 0, 4, Charsets.US_ASCII) == "RIFF" && String(data, 8, 4, Charsets.US_ASCII) == "WEBP" -> stripWebp(data)
        else -> throw IOException("Not a JPEG, PNG or WebP picture")
    }

    private val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

    private fun u16(d: ByteArray, i: Int) = (d[i].toInt() and 0xFF shl 8) or (d[i + 1].toInt() and 0xFF)

    /**
     * Keeps the segments that decode the picture (tables, frame, scans), JFIF, the color profile and
     * Adobe's color info. Drops EXIF and XMP (APP1), IPTC and Photoshop data (APP13), other APPn and
     * comments, and anything after the end of the picture (motion photo videos, gain maps).
     */
    private fun stripJpeg(d: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(d.size)
        out.write(d, 0, 2)
        var i = 2
        while (i + 4 <= d.size) {
            if (d[i] != 0xFF.toByte()) throw IOException("Broken JPEG")
            val marker = d[i + 1].toInt() and 0xFF
            if (marker == 0xFF) { i++; continue }
            if (marker == 0xD9) { out.write(d, i, 2); return out.toByteArray() }
            val length = u16(d, i + 2)
            val end = i + 2 + length
            if (length < 2 || end > d.size) throw IOException("Broken JPEG")
            if (keepJpegSegment(marker, d, i + 4, length - 2)) out.write(d, i, end - i)
            i = end
            if (marker == 0xDA) {
                // Picture data up to the next marker; FF 00 is an escaped byte, FF D0..D7 restart markers.
                val start = i
                while (i + 1 < d.size) {
                    if (d[i] == 0xFF.toByte()) {
                        val next = d[i + 1].toInt() and 0xFF
                        if (next != 0x00 && next !in 0xD0..0xD7) break
                    }
                    i++
                }
                out.write(d, start, i - start)
            }
        }
        throw IOException("Broken JPEG")
    }

    private fun keepJpegSegment(marker: Int, d: ByteArray, at: Int, size: Int): Boolean = when (marker) {
        // JFIF without a thumbnail: a thumbnail may still show the picture before it was cropped.
        0xE0 -> size == 14 && String(d, at, 5, Charsets.US_ASCII) == "JFIF\u0000"
        0xE2 -> size >= 12 && String(d, at, 12, Charsets.US_ASCII) == "ICC_PROFILE\u0000"
        0xEE -> true // Adobe: how the colors are stored
        in 0xE1..0xEF, 0xFE -> false
        else -> true
    }

    // Only what drawing the picture needs; text, EXIF, time, content credentials and private chunks go.
    private val PNG_KEEP = setOf(
        "IHDR", "PLTE", "IDAT", "IEND", "tRNS", "gAMA", "cHRM", "sRGB", "iCCP", "sBIT", "bKGD", "pHYs", "acTL", "fcTL", "fdAT"
    )

    private fun stripPng(d: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(d.size)
        out.write(d, 0, 8)
        var i = 8
        while (i + 12 <= d.size) {
            val length = ((d[i].toInt() and 0xFF) shl 24) or ((d[i + 1].toInt() and 0xFF) shl 16) or
                ((d[i + 2].toInt() and 0xFF) shl 8) or (d[i + 3].toInt() and 0xFF)
            val type = String(d, i + 4, 4, Charsets.US_ASCII)
            val end = i + 12 + length
            if (length < 0 || end > d.size) throw IOException("Broken PNG")
            if (type in PNG_KEEP) out.write(d, i, end - i)
            i = end
            if (type == "IEND") return out.toByteArray()
        }
        throw IOException("Broken PNG")
    }

    private val WEBP_KEEP = setOf("VP8 ", "VP8L", "VP8X", "ALPH", "ANIM", "ANMF", "ICCP")

    /** Keeps only the picture chunks; EXIF, XMP and anything unknown go, with their header flags. */
    private fun stripWebp(d: ByteArray): ByteArray {
        val body = ByteArrayOutputStream(d.size)
        var i = 12
        while (i + 8 <= d.size) {
            val fourcc = String(d, i, 4, Charsets.US_ASCII)
            val size = (d[i + 4].toInt() and 0xFF) or ((d[i + 5].toInt() and 0xFF) shl 8) or
                ((d[i + 6].toInt() and 0xFF) shl 16) or ((d[i + 7].toInt() and 0xFF) shl 24)
            val end = i + 8 + size + (size and 1)
            if (size < 0 || i + 8 + size > d.size) throw IOException("Broken WebP")
            when (fourcc) {
                !in WEBP_KEEP -> {}
                "VP8X" -> {
                    val chunk = d.copyOfRange(i, minOf(end, d.size))
                    chunk[8] = (chunk[8].toInt() and 0x08.inv() and 0x04.inv()).toByte()
                    body.write(chunk)
                }
                else -> body.write(d, i, minOf(end, d.size) - i)
            }
            i = end
        }
        val payload = body.toByteArray()
        val riffSize = payload.size + 4
        return ByteArrayOutputStream(payload.size + 12).apply {
            write("RIFF".toByteArray(Charsets.US_ASCII))
            write(byteArrayOf(riffSize.toByte(), (riffSize shr 8).toByte(), (riffSize shr 16).toByte(), (riffSize shr 24).toByte()))
            write("WEBP".toByteArray(Charsets.US_ASCII))
            write(payload)
        }.toByteArray()
    }
}
