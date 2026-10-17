package com.nexus.filemanager.data.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdaterTest {
    @Test
    fun versionsCompareNumerically() {
        assertTrue(Updater.isNewer("1.10.0", "1.9.2"))
        assertTrue(Updater.isNewer("1.1", "1.0.9"))
        assertTrue(Updater.isNewer("2.0.0", "1.99.99"))
        assertFalse(Updater.isNewer("1.0.0", "1.0.0"))
        assertFalse(Updater.isNewer("1.0", "1.0.0"))
        assertFalse(Updater.isNewer("0.9", "1.0.0"))
    }
}
