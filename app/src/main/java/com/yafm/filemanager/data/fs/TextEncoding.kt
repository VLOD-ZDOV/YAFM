package com.yafm.filemanager.data.fs

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/** Which encoding a text file is in, so it reads right and is written back the same way. */
object TextEncoding {

    /** What the editor offers to read a file as. */
    val CHOICES: List<Charset> = listOf("UTF-8", "windows-1251", "KOI8-R", "IBM866", "UTF-16LE", "UTF-16BE", "windows-1252")
        .mapNotNull { runCatching { Charset.forName(it) }.getOrNull() }

    /** The encoding of [data], or null for a binary file that must not be saved as text. */
    fun detect(data: ByteArray): Charset? {
        when {
            data.startsWith(0xEF, 0xBB, 0xBF) -> return Charsets.UTF_8
            data.startsWith(0xFF, 0xFE) -> return Charsets.UTF_16LE
            data.startsWith(0xFE, 0xFF) -> return Charsets.UTF_16BE
        }
        // NUL bytes are valid UTF-8 too, but text never has them.
        if (data.any { it == 0.toByte() }) return null
        if (isUtf8(data)) return Charsets.UTF_8
        // An old 8-bit file: the encoding whose letters look most like real words wins.
        return CHOICES.filter { it.name() in EIGHT_BIT }.maxByOrNull { score(String(data, it)) } ?: Charsets.ISO_8859_1
    }

    private val EIGHT_BIT = setOf("windows-1251", "KOI8-R", "IBM866", "windows-1252")

    // The most common Russian letters (lower case is far more frequent in text) and Western accents.
    private const val COMMON = "оеаинтсрвлкмдпуяызбгчйхжшюцщэфъёь"
    private const val LATIN = "éèàùâêîôûçäöüßñáíóú"

    private fun score(text: String): Int = text.sumOf { c ->
        when {
            c in COMMON -> 3
            c in LATIN -> 2
            c.lowercaseChar() in COMMON -> 1
            c.isLetter() || c.isWhitespace() || c in ".,;:!?-()\"'" -> 0
            else -> -2
        }.toInt()
    }

    private fun isUtf8(data: ByteArray): Boolean = try {
        Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(data))
        true
    } catch (_: CharacterCodingException) {
        false
    }

    private fun ByteArray.startsWith(vararg bytes: Int) =
        size >= bytes.size && bytes.indices.all { this[it] == bytes[it].toByte() }

    /** A byte order mark to keep when the file had one. */
    fun bom(data: ByteArray): ByteArray = when {
        data.startsWith(0xEF, 0xBB, 0xBF) -> data.copyOf(3)
        data.startsWith(0xFF, 0xFE) || data.startsWith(0xFE, 0xFF) -> data.copyOf(2)
        else -> ByteArray(0)
    }

    /** The text without its byte order mark, read as [charset]. */
    fun decode(data: ByteArray, charset: Charset): String {
        val skip = bom(data).size.takeIf { b -> b > 0 && charset.name().startsWith("UTF") } ?: 0
        return String(data, skip, data.size - skip, charset)
    }

    /** Null when some character of [text] has no place in [charset]. */
    fun encode(text: String, charset: Charset): ByteArray? = try {
        val buf = charset.newEncoder().onUnmappableCharacter(CodingErrorAction.REPORT).encode(java.nio.CharBuffer.wrap(text))
        ByteArray(buf.remaining()).also { buf.get(it) }
    } catch (_: CharacterCodingException) {
        null
    }
}
