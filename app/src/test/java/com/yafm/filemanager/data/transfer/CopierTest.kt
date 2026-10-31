package com.yafm.filemanager.data.transfer

import com.yafm.filemanager.data.fs.FileSystem
import com.yafm.filemanager.data.fs.LocalFileSystem
import com.yafm.filemanager.data.fs.RootFileSystem
import com.yafm.filemanager.data.model.FileItem
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.FilterOutputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.file.Files
import kotlin.random.Random

class CopierTest {
    private val base = Files.createTempDirectory("copier").toFile()
    private val from = base.resolve("from").apply { mkdir() }
    private val to = base.resolve("to").apply { mkdir() }
    private val local = LocalFileSystem(RootFileSystem()) { false }
    private val otherStorage = Other(local)
    private val reports = mutableListOf<TransferProgress>()

    /** Another storage: not the same object as [local], and it can lose its connection. */
    private class Other(val fs: FileSystem) : FileSystem by fs {
        var failAfter = Long.MAX_VALUE
        var connected = true
        var failures = 0
        override val isRemote get() = true
        override val isOpen get() = connected
        override val canAppend get() = true
        override fun openRead(path: String, offset: Long) = fs.openRead(path, offset)
        override fun openAppend(path: String) = guarded(fs.openAppend(path)!!)
        override fun openWrite(path: String) = guarded(fs.openWrite(path))
        override fun stat(path: String) = fs.stat(path)
        override fun replace(from: String, to: String) = fs.replace(from, to)
        override fun setModified(path: String, millis: Long) = fs.setModified(path, millis)

        private fun guarded(out: OutputStream) = object : FilterOutputStream(out) {
            override fun write(b: ByteArray, off: Int, len: Int) {
                if (failAfter <= 0) {
                    failures++
                    connected = false
                    failAfter = Long.MAX_VALUE
                    throw IOException("connection lost")
                }
                failAfter -= len
                out.write(b, off, len)
            }
        }
    }

    private val runner = object : Runner {
        override suspend fun <T> run(block: () -> T): T {
            otherStorage.connected = true
            return block()
        }
    }

    @After
    fun clean() {
        base.deleteRecursively()
    }

    private fun item(file: File) = FileItem(
        name = file.name, path = file.path, size = if (file.isDirectory) 0 else file.length(),
        isDirectory = file.isDirectory, lastModified = file.lastModified()
    )

    private fun copier(policy: OnConflict, total: Long = 0) =
        Copier(otherStorage, policy, TransferMeter(1, total) { reports += it }, runner, retryDelaysMs = listOf(1, 1))

    private fun copy(file: File, policy: OnConflict = OnConflict.KEEP_BOTH, move: Boolean = false): Copier {
        val c = copier(policy, file.length())
        val taken = local.list(to.path).associateBy { it.name }.toMutableMap()
        runBlocking { c.place(local, item(file), to.path, move, taken, file.length()) }
        return c
    }

    @Test
    fun replacesAndKeepsTheFileIntactUntilTheNewOneIsWhole() {
        val source = from.resolve("a.bin").apply { writeBytes(Random.nextBytes(1_000_000)) }
        val old = to.resolve("a.bin").apply { writeText("old") }
        otherStorage.failAfter = 300_000
        // With no retries left a failure leaves the old file as it was.
        val c = Copier(otherStorage, OnConflict.REPLACE, TransferMeter(1, 0) {}, runner, retryDelaysMs = emptyList())
        assertThrows(IOException::class.java) {
            runBlocking { c.place(local, item(source), to.path, false, local.list(to.path).associateBy { it.name }.toMutableMap(), 0) }
        }
        assertEquals("old", old.readText())
        assertTrue("the partial file is kept for a retry", to.resolve(".a.bin.yafm-part").exists())

        copy(source, OnConflict.REPLACE)
        assertArrayEquals(source.readBytes(), old.readBytes())
        assertFalse(to.resolve(".a.bin.yafm-part").exists())
    }

    @Test
    fun aLostConnectionGoesOnFromWhereItStopped() {
        val data = Random.nextBytes(2_000_000)
        val source = from.resolve("big.bin").apply { writeBytes(data) }
        otherStorage.failAfter = 900_000
        copy(source)
        assertEquals(1, otherStorage.failures)
        assertArrayEquals(data, to.resolve("big.bin").readBytes())
        assertTrue("the transfer said it reconnects", reports.any { it.reconnecting })
        assertEquals("nothing is counted twice", data.size.toLong(), reports.last().bytesDone)
    }

    @Test
    fun aPartialFileOfAnotherFileIsNotContinued() {
        val data = Random.nextBytes(500_000)
        val source = from.resolve("x.bin").apply { writeBytes(data) }
        to.resolve(".x.bin.yafm-part").writeBytes(ByteArray(100_000) { 7 })
        copy(source)
        assertArrayEquals(data, to.resolve("x.bin").readBytes())
    }

    @Test
    fun keepsBothSkipsAndSkipsSame() {
        val source = from.resolve("n.txt").apply { writeText("new") }
        to.resolve("n.txt").writeText("old")
        copy(source, OnConflict.SKIP)
        assertEquals("old", to.resolve("n.txt").readText())
        copy(source, OnConflict.SKIP_SAME)
        assertEquals("old (same size)", "old", to.resolve("n.txt").readText())
        copy(source, OnConflict.KEEP_BOTH)
        assertEquals("new", to.resolve("n (1).txt").readText())
    }

    @Test
    fun foldersAreMergedAndMovedOnlyIfAllArrived() {
        val dir = from.resolve("d").apply { mkdir() }
        dir.resolve("one.txt").writeText("1")
        val sub = dir.resolve("sub").apply { mkdir() }
        sub.resolve("two.txt").writeText("2")
        Files.createSymbolicLink(dir.resolve("loop").toPath(), dir.toPath())
        to.resolve("d").mkdir()
        to.resolve("d/keep.txt").writeText("k")

        val c = copy(dir, OnConflict.REPLACE, move = true)
        assertEquals("1", to.resolve("d/one.txt").readText())
        assertEquals("2", to.resolve("d/sub/two.txt").readText())
        assertEquals("k", to.resolve("d/keep.txt").readText())
        assertEquals(1, c.skippedLinks)
        assertTrue("a link that was not copied keeps its folder", dir.exists())
    }

    @Test
    fun movingAcrossStoragesRemovesTheSource() {
        val source = from.resolve("m.txt").apply { writeText("m") }
        copy(source, move = true)
        assertEquals("m", to.resolve("m.txt").readText())
        assertFalse(source.exists())
    }

    @Test
    fun copyingOntoItselfMakesACopy() {
        val file = to.resolve("s.txt").apply { writeText("same") }
        val c = Copier(local, OnConflict.REPLACE, TransferMeter(1, 0) {}, runner)
        runBlocking { c.place(local, item(file), to.path, false, local.list(to.path).associateBy { it.name }.toMutableMap(), 0) }
        assertEquals("same", file.readText())
        assertEquals("same", to.resolve("s (1).txt").readText())
    }

    @Test
    fun meterEstimatesTimeLeft() {
        var now = 0L
        var last = TransferProgress()
        val meter = TransferMeter(1, 10_000_000, clock = { now }) { last = it }
        meter.startFile("f")
        repeat(4) { now += 500; meter.add(500_000) }
        meter.finishFile()
        assertEquals(1_000_000L, last.bytesPerSecond)
        assertEquals(8L, last.secondsLeft)
    }
}
