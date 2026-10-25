package com.yafm.filemanager.data.fs

import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/** Tells text from binary by the file's start, whatever its name says. */
object TextSniffer {

    fun looksLikeText(file: File): Boolean = runCatching {
        val head = ByteArray(8192)
        val n = file.inputStream().use { it.read(head) }
        if (n <= 0) return true
        looksLikeText(head.copyOf(n))
    }.getOrDefault(false)

    fun looksLikeText(data: ByteArray): Boolean {
        if (data.any { it == 0.toByte() }) return false
        // A cut multi-byte letter at the end of the sample is not a reason to call it binary.
        val trimmed = data.copyOf(maxOf(0, data.size - 3)).takeIf { data.size > 3 } ?: data
        val utf8 = try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(trimmed)); true
        } catch (_: CharacterCodingException) { false }
        if (utf8) return true
        // Other 8-bit encodings (cp1251...): mostly printable bytes.
        val control = data.count { val b = it.toInt() and 0xFF; b < 0x09 || b in 0x0E..0x1F }
        return control * 20 < data.size
    }
}
