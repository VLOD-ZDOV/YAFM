package com.yafm.filemanager.data.archive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class RemoteArchiveTest {
    private fun sh(command: String): Int = ProcessBuilder("sh", "-c", command).redirectErrorStream(true).start().run {
        inputStream.readBytes()
        waitFor()
    }

    @Test
    fun packsAndUnpacksNamesAShellWouldMisread() {
        assumeTrue(File("/bin/tar").exists() || File("/usr/bin/tar").exists())
        val dir = Files.createTempDirectory("rem it's").toFile()
        try {
            val tricky = dir.resolve("-rf \$(touch pwned) 'q'.txt").apply { writeText("data") }
            val archive = dir.resolve("out.tar.gz").path
            assertEquals(0, sh(RemoteArchive.compress(ArchiveManager.Format.TAR_GZ, dir.path, listOf(tricky.name), archive)))
            val target = dir.resolve("unpacked").path
            assertEquals(0, sh(RemoteArchive.extract(archive, target)!!))
            assertEquals("data", File(target, tricky.name).readText())
            assertTrue("nothing was run from the name", dir.list()!!.none { it == "pwned" })
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun folderNameDropsTheSuffix() {
        assertEquals("backup", RemoteArchive.folderName("backup.tar.gz"))
        assertEquals("a.b", RemoteArchive.folderName("a.b.zip"))
    }
}
