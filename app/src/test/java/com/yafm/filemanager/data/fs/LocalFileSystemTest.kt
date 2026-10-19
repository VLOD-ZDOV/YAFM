package com.yafm.filemanager.data.fs

import com.yafm.filemanager.data.model.FileItem
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class LocalFileSystemTest {

    @Test
    fun deleteRemovesLinksNotTheirTargets() {
        val base = Files.createTempDirectory("lfs").toFile()
        try {
            val victim = base.resolve("victim").apply { mkdir() }
            val keep = victim.resolve("keep.txt").apply { writeText("data") }
            val doomed = base.resolve("doomed").apply { mkdir() }
            doomed.resolve("own.txt").writeText("x")
            Files.createSymbolicLink(doomed.resolve("link").toPath(), victim.toPath())

            val fs = LocalFileSystem(RootFileSystem()) { false }
            fs.delete(FileItem(name = "doomed", path = doomed.path, isDirectory = true))

            assertFalse(doomed.exists())
            assertTrue("the link's target must survive", keep.exists())
        } finally {
            base.deleteRecursively()
        }
    }
}
