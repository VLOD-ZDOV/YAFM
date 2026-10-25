package com.yafm.filemanager.data.fs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.charset.Charset

class TextEncodingTest {
    private val text = "Привет! Это обычный текстовый файл с настройками, который сохранили давным-давно.\n"

    @Test
    fun findsTheEncoding() {
        assertEquals(Charsets.UTF_8, TextEncoding.detect(text.toByteArray()))
        listOf("windows-1251", "KOI8-R", "IBM866").forEach { name ->
            assertEquals(name, Charset.forName(name), TextEncoding.detect(text.toByteArray(Charset.forName(name))))
        }
        assertEquals(Charsets.UTF_16LE, TextEncoding.detect(byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + "hi".toByteArray(Charsets.UTF_16LE)))
        assertNull("binary", TextEncoding.detect(byteArrayOf(0x4D, 0x5A, 0, 0, 1, 2, 3)))
    }

    @Test
    fun roundTripsAndRefusesWhatDoesNotFit() {
        val cp1251 = Charset.forName("windows-1251")
        assertEquals(text, TextEncoding.decode(TextEncoding.encode(text, cp1251)!!, cp1251))
        assertNull(TextEncoding.encode("emoji 🙂", cp1251))
    }

    @Test
    fun readsLongFilesWhole() {
        // Longer than the decoding buffer and the sample: a bad byte at the very end still counts.
        val long = text.repeat(2000).toByteArray()
        assertEquals(Charsets.UTF_8, TextEncoding.detect(long))
        assertNotEquals(Charsets.UTF_8, TextEncoding.detect(long + byteArrayOf(0xC0.toByte(), 0x41)))
        // A multi-byte letter cut off at the end is not UTF-8.
        assertNotEquals(Charsets.UTF_8, TextEncoding.detect(long + "Я".toByteArray().copyOf(1)))
    }
}
