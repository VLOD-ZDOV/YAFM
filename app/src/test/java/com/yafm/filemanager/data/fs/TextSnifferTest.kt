package com.yafm.filemanager.data.fs

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TextSnifferTest {
    @Test
    fun tellsTextFromBinary() {
        assertTrue(TextSniffer.looksLikeText("key = value\n# comment\n".toByteArray()))
        assertTrue(TextSniffer.looksLikeText("Привет, мир\n".toByteArray()))
        assertTrue("cp1251", TextSniffer.looksLikeText("Привет, мир\n".toByteArray(charset("windows-1251"))))
        assertFalse(TextSniffer.looksLikeText(byteArrayOf(0x4D, 0x5A, 0x90.toByte(), 0, 3, 0, 0, 0)))
        assertFalse(TextSniffer.looksLikeText(ByteArray(64) { (it * 37 % 32).toByte() }))
    }
}
