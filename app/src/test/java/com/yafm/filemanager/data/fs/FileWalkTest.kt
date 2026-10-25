package com.yafm.filemanager.data.fs

import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.file.Files

class FileWalkTest {

    @Test
    fun visitsFilesOnlyAndNeverFollowsLinks() {
        val base = Files.createTempDirectory("walk").toFile()
        try {
            val outside = Files.createTempDirectory("outside").toFile()
            outside.resolve("secret.txt").writeText("x")
            base.resolve("a.txt").writeText("12345")
            base.resolve("sub").mkdir()
            base.resolve("sub/b.txt").writeText("1")
            base.resolve(".hidden").mkdir()
            base.resolve(".hidden/c.txt").writeText("1")
            Files.createSymbolicLink(base.resolve("dirlink").toPath(), outside.toPath())
            Files.createSymbolicLink(base.resolve("filelink").toPath(), base.resolve("a.txt").toPath())

            val all = mutableMapOf<String, Long>()
            walkFiles(base.path) { path, attrs -> all[base.toPath().relativize(path).toString()] = attrs.size() }
            assertEquals(mapOf("a.txt" to 5L, "sub/b.txt" to 1L, ".hidden/c.txt" to 1L), all)

            val shown = mutableListOf<String>()
            walkFiles(base.path, enter = { !it.isHidden }) { path, _ -> shown += path.fileName.toString() }
            assertEquals(setOf("a.txt", "b.txt"), shown.toSet())
            outside.deleteRecursively()
        } finally {
            base.deleteRecursively()
        }
    }
}
