package com.yafm.filemanager.data.stream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StreamServerTest {
    private fun range(h: String, size: Long = 1000) = StreamServer.parseRange(h, size)

    @Test fun fullRanges() {
        assertEquals(0L..999L, range("bytes=0-"))
        assertEquals(100L..199L, range("bytes=100-199"))
        assertEquals(500L..999L, range("bytes=500-5000"))
    }

    @Test fun suffixRange() {
        assertEquals(900L..999L, range("bytes=-100"))
        assertEquals(0L..999L, range("bytes=-5000"))
    }

    @Test fun badRanges() {
        assertNull(range("bytes=1000-"))
        assertNull(range("bytes=200-100"))
        assertNull(range("items=0-1"))
        assertNull(range("bytes=abc"))
        assertNull(range("bytes=0-", size = 0))
    }
}
