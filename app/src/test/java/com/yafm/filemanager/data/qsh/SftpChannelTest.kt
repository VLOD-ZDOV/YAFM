package com.yafm.filemanager.data.qsh

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.random.Random

/**
 * Runs the client against a real SFTP server on stdin/stdout: OpenSSH's sftp-server by default,
 * or any command in SFTP_TEST_CMD (e.g. `qsh -s user@host sftp`). With SFTP_TEST_DIR the files
 * go there instead of a local temporary directory (needed for a remote server).
 */
class SftpChannelTest {
    private lateinit var process: Process
    private lateinit var sftp: SftpChannel
    private lateinit var dir: String
    private var localDir: File? = null

    @Before
    fun start() {
        val cmd = System.getenv("SFTP_TEST_CMD") ?: "/usr/lib/ssh/sftp-server"
        assumeTrue("no SFTP server to test against", System.getenv("SFTP_TEST_CMD") != null || File(cmd).canExecute())
        process = ProcessBuilder("sh", "-c", cmd).redirectError(ProcessBuilder.Redirect.INHERIT).start()
        sftp = SftpChannel(process.inputStream, process.outputStream)
        dir = System.getenv("SFTP_TEST_DIR") ?: Files.createTempDirectory("sftp").toFile().also { localDir = it }.absolutePath
        dir = "$dir/t${System.nanoTime()}"
        sftp.mkdir(dir)
    }

    @After
    fun stop() {
        if (::sftp.isInitialized) {
            runCatching { sftp.list(dir).forEach { sftp.remove("$dir/${it.name}") } }
            runCatching { sftp.rmdir(dir) }
            sftp.close()
            process.destroy()
        }
        localDir?.deleteRecursively()
    }

    @Test
    fun roundTripLargeFile() {
        val data = Random(1).nextBytes(3 * 1024 * 1024 + 123)
        sftp.openOutputStream("$dir/big.bin").use { it.write(data) }
        assertEquals(data.size.toLong(), sftp.stat("$dir/big.bin").size)
        val back = sftp.openInputStream("$dir/big.bin").use { it.readBytes() }
        assertArrayEquals(data, back)
    }

    @Test
    fun smallWritesAndEmptyFile() {
        sftp.openOutputStream("$dir/small.txt").use { out -> "hello\n".forEach { out.write(it.code) } }
        assertEquals("hello\n", sftp.openInputStream("$dir/small.txt").use { String(it.readBytes()) })
        sftp.openOutputStream("$dir/empty", exclusive = true).close()
        assertEquals(0, sftp.openInputStream("$dir/empty").use { it.readBytes() }.size)
    }

    @Test
    fun listRenameChmodRemove() {
        sftp.mkdir("$dir/sub")
        sftp.openOutputStream("$dir/a b'c").use { it.write(byteArrayOf(1, 2, 3)) }
        val names = sftp.list(dir).associateBy { it.name }
        assertTrue(names.getValue("sub").attrs.isDirectory)
        assertFalse(names.getValue("a b'c").attrs.isDirectory)
        assertEquals(3L, names.getValue("a b'c").attrs.size)

        sftp.rename("$dir/a b'c", "$dir/renamed")
        sftp.chmod("$dir/renamed", "600".toInt(8))
        assertEquals("600".toInt(8), sftp.stat("$dir/renamed").permissions!! and 0x1ff)

        sftp.remove("$dir/renamed")
        sftp.rmdir("$dir/sub")
        assertTrue(sftp.list(dir).isEmpty())
    }

    @Test
    fun errors() {
        val e = runCatching { sftp.openInputStream("$dir/missing") }.exceptionOrNull()
        assertTrue(e is SftpChannel.SftpException && e.code == 2)
        val excl = runCatching {
            sftp.openOutputStream("$dir/x").close()
            sftp.openOutputStream("$dir/x", exclusive = true)
        }.exceptionOrNull()
        assertTrue(excl is SftpChannel.SftpException)
        sftp.remove("$dir/x")
    }

    @Test
    fun concurrentRequests() {
        val files = (1..8).map { "$dir/f$it" }
        files.forEachIndexed { i, f -> sftp.openOutputStream(f).use { it.write(ByteArray(100_000) { i.toByte() }) } }
        val results = files.map { f -> Thread { sftp.openInputStream(f).use { it.readBytes() } } }
        results.forEach { it.start() }
        results.forEach { it.join() }
        files.forEachIndexed { i, f ->
            assertTrue(sftp.openInputStream(f).use { it.readBytes() }.all { it == i.toByte() })
            sftp.remove(f)
        }
        assertEquals("/", sftp.realpath("/"))
    }
}
