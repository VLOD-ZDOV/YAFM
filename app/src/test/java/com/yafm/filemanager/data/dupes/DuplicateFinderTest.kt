package com.yafm.filemanager.data.dupes

import com.yafm.filemanager.data.model.FileItem
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.nio.file.Files

class DuplicateFinderTest {

    private fun item(f: File) = FileItem(name = f.name, path = f.path, size = f.length(), lastModified = f.lastModified())

    @Test
    fun findsSameContentUnderOtherNamesOnly() {
        val base = Files.createTempDirectory("dupes").toFile()
        try {
            val a = base.resolve("a").apply { mkdir() }
            val b = base.resolve("b").apply { mkdir() }
            val photo = a.resolve("photo.jpg").apply { writeText("same bytes") }
            val copy = b.resolve("IMG_0001.jpg").apply { writeText("same bytes") }
            val other = b.resolve("other.jpg").apply { writeText("diff bytes") }
            // Larger than the head that is read first, differing only at the end.
            val big = ByteArray(200_000) { 7 }
            val big1 = a.resolve("big1").apply { writeBytes(big) }
            val big2 = a.resolve("big2").apply { writeBytes(big.copyOf().also { it[it.size - 1] = 8 }) }
            val big3 = b.resolve("big3").apply { writeBytes(big) }

            val groups = DuplicateFinder.find(listOf(photo, copy, other, big1, big2, big3).map(::item))

            assertEquals(
                setOf(setOf(photo.path, copy.path), setOf(big1.path, big3.path)),
                groups.map { g -> g.map { it.path }.toSet() }.toSet()
            )
            assertEquals("most space wasted first", big1.length(), groups[0][0].size)
        } finally {
            base.deleteRecursively()
        }
    }
}
