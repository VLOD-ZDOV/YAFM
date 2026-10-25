package com.yafm.filemanager.data.archive

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.random.Random

class ArchiveManagerTest {
    private lateinit var base: File
    private lateinit var src: File

    @Before
    fun setUp() {
        base = Files.createTempDirectory("arch").toFile()
        src = File(base, "data").apply { mkdirs() }
        File(src, "a.txt").writeText("hello")
        File(src, "sub/deep").mkdirs()
        File(src, "sub/deep/b.bin").writeBytes(Random(7).nextBytes(300_000))
        File(src, "имя с пробелом.txt").writeText("юникод")
    }

    @After
    fun tearDown() {
        base.deleteRecursively()
    }

    private fun assertSameTree(expected: File, actual: File) {
        val e = expected.walkTopDown().filter { it.isFile }.map { it.relativeTo(expected).path }.sorted().toList()
        val a = actual.walkTopDown().filter { it.isFile }.map { it.relativeTo(actual).path }.sorted().toList()
        assertEquals(e, a)
        e.forEach { assertTrue(it, File(expected, it).readBytes().contentEquals(File(actual, it).readBytes())) }
    }

    @Test
    fun existingFilesAreKeptSkippedOrReplaced() = runBlocking {
        val archive = File(base, "out.zip")
        ArchiveManager.createArchive(listOf(src), archive, ArchiveManager.Format.ZIP)
        val out = File(base, "x").apply { File(this, "data").mkdirs() }
        val mine = File(out, "data/a.txt").apply { writeText("mine") }

        ArchiveManager.extractArchive(archive, out, ArchiveManager.Conflict.KEEP_BOTH)
        assertEquals("mine", mine.readText())
        assertEquals("hello", File(out, "data/a (1).txt").readText())

        ArchiveManager.extractArchive(archive, out, ArchiveManager.Conflict.SKIP)
        assertEquals("mine", mine.readText())
        assertFalse(File(out, "data/a (2).txt").exists())

        ArchiveManager.extractArchive(archive, out, ArchiveManager.Conflict.REPLACE)
        assertEquals("hello", mine.readText())
    }

    @Test
    fun keepingBothNeverWritesThroughALink() = runBlocking {
        val archive = File(base, "out.zip")
        ArchiveManager.createArchive(listOf(src), archive, ArchiveManager.Format.ZIP)
        val out = File(base, "x").apply { File(this, "data").mkdirs() }
        File(out, "data/a.txt").writeText("mine")
        val victim = File(base, "victim.txt")
        // A dangling link on the name a second copy would get.
        Files.createSymbolicLink(File(out, "data/a (1).txt").toPath(), victim.toPath())

        ArchiveManager.extractArchive(archive, out, ArchiveManager.Conflict.KEEP_BOTH)

        assertFalse("nothing written through the link", victim.exists())
        assertEquals("hello", File(out, "data/a (2).txt").readText())
    }

    @Test
    fun archivesAfterAProgramAreFound() = runBlocking {
        // A self-extracting .exe is a program with an archive after it.
        val stub = "MZ".toByteArray() + ByteArray(5000) { (it % 251).toByte() }
        listOf(ArchiveManager.Format.ZIP, ArchiveManager.Format.SEVEN_Z).forEach { format ->
            val archive = File(base, "plain${format.extension}")
            ArchiveManager.createArchive(listOf(src), archive, format)
            val exe = File(base, "setup-${format.name}.exe").apply { writeBytes(stub + archive.readBytes()) }
            val out = File(base, "sfx-${format.name}")
            ArchiveManager.extractArchive(exe, out)
            assertSameTree(src, File(out, "data"))
        }
    }

    @Test
    fun contentsAreListedAndOneFileComesOut() = runBlocking {
        listOf(ArchiveManager.Format.ZIP, ArchiveManager.Format.SEVEN_Z, ArchiveManager.Format.TAR_GZ).forEach { format ->
            val archive = File(base, "view${format.extension}")
            ArchiveManager.createArchive(listOf(src), archive, format)
            val entries = ArchiveContents.list(archive)

            assertEquals(listOf("data"), ArchiveContents.children(entries, "").map { it.name })
            assertEquals(listOf("sub", "a.txt", "имя с пробелом.txt"), ArchiveContents.children(entries, "data").map { it.name })
            assertEquals(listOf("b.bin"), ArchiveContents.children(entries, "data/sub/deep").map { it.name })

            val out = ArchiveContents.extractOne(archive, "data/sub/deep/b.bin", File(base, "one-${format.name}"))
            assertTrue(out.readBytes().contentEquals(File(src, "sub/deep/b.bin").readBytes()))
            assertFalse("nothing else extracted", File(base, "one-${format.name}/a.txt").exists())
        }
    }

    @Test
    fun everyFormatRoundTrips() = runBlocking {
        ArchiveManager.Format.entries.forEach { format ->
            val archive = File(base, "out${format.extension}")
            ArchiveManager.createArchive(listOf(src), archive, format)
            val out = File(base, "x-${format.name}")
            ArchiveManager.extractArchive(archive, out)
            assertSameTree(src, File(out, "data"))
        }
    }

    @Test
    fun formatIsDetectedByContent() = runBlocking {
        val archive = File(base, "out.tar.lz4")
        ArchiveManager.createArchive(listOf(src), archive, ArchiveManager.Format.TAR_LZ4)
        val renamed = File(base, "misleading.zip")
        archive.renameTo(renamed)
        val out = File(base, "x")
        ArchiveManager.extractArchive(renamed, out)
        assertSameTree(src, File(out, "data"))
    }

    private fun sh(vararg cmd: String) {
        val p = ProcessBuilder(*cmd).directory(base).redirectErrorStream(true).start()
        val output = p.inputStream.bufferedReader().readText()
        assertEquals(output, 0, p.waitFor())
    }

    @Test
    fun archivesFromSystemTools() = runBlocking {
        listOf("lz4" to "tar.lz4", "zstd" to "tar.zst", "xz" to "tar.xz").forEach { (tool, ext) ->
            assumeTrue("$tool not installed", File("/usr/bin/$tool").canExecute())
            sh("tar", "-I", tool, "-cf", "sys.$ext", "data")
            val out = File(base, "sys-$tool")
            ArchiveManager.extractArchive(File(base, "sys.$ext"), out)
            assertSameTree(src, File(out, "data"))
        }
    }

    @Test
    fun singleCompressedFile() = runBlocking {
        assumeTrue(File("/usr/bin/lz4").canExecute())
        sh("lz4", "-q", "data/a.txt", "single.txt.lz4")
        val out = File(base, "single")
        ArchiveManager.extractArchive(File(base, "single.txt.lz4"), out)
        assertEquals("hello", File(out, "single.txt").readText())
    }

    @Test
    fun linksAndEscapesAreSkipped() = runBlocking {
        val evil = File(base, "evil").apply { mkdirs() }
        File(evil, "ok.txt").writeText("ok")
        Files.createSymbolicLink(File(evil, "link").toPath(), File("/etc").toPath())
        sh("tar", "-cf", "evil.tar", "evil")
        // Add an entry that climbs out of the target.
        sh("tar", "--transform", "s|^|../../|", "-rf", "evil.tar", "data/a.txt")
        val out = File(base, "out/inner")
        ArchiveManager.extractArchive(File(base, "evil.tar"), out)
        assertTrue(File(out, "evil/ok.txt").exists())
        assertFalse(Files.exists(File(out, "evil/link").toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS))
        assertFalse(File(base, "data/a.txt.escaped").exists())
        assertFalse(File(out, "../../data/a.txt").canonicalFile.let { it.exists() && it.readText() != "hello" })
    }
}
