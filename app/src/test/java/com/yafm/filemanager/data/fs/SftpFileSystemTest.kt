package com.yafm.filemanager.data.fs

import com.yafm.filemanager.data.model.FileItem
import com.yafm.filemanager.data.model.ServerConfig
import com.yafm.filemanager.data.model.StorageType
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.ServerSocket
import java.nio.file.Files
import kotlin.random.Random

/** Against a real OpenSSH server run for the test as this user, on a free local port. */
class SftpFileSystemTest {
    private val base = Files.createTempDirectory("sshd").toFile()
    private val data = base.resolve("data").apply { mkdir() }
    private lateinit var sshd: Process
    private lateinit var config: ServerConfig
    private lateinit var fs: SftpFileSystem
    private val asked = mutableListOf<Pair<String, Boolean>>()
    private var trust = true

    private val prompts = object : LoginPrompts {
        override fun trustHostKey(server: ServerConfig, fingerprint: String, changed: Boolean): Boolean {
            asked += fingerprint to changed
            return trust
        }
        override fun answer(server: ServerConfig, prompt: String): String? = null
    }

    private fun run(vararg args: String) = ProcessBuilder(*args).redirectErrorStream(true).start().apply { inputStream.readBytes(); waitFor() }

    @Before
    fun start() {
        val server = "/usr/bin/sshd"
        assumeTrue(File(server).canExecute() && File("/usr/lib/ssh/sftp-server").canExecute())
        base.setReadable(false, false); base.setReadable(true, true)
        run("ssh-keygen", "-q", "-t", "ed25519", "-N", "", "-f", "$base/host")
        run("ssh-keygen", "-q", "-t", "ed25519", "-N", "", "-f", "$base/user")
        val keys = base.resolve("authorized_keys").apply { writeText(base.resolve("user.pub").readText()) }
        keys.setReadable(false, false); keys.setReadable(true, true)
        val port = ServerSocket(0).use { it.localPort }
        val cfg = base.resolve("sshd_config")
        cfg.writeText(
            """
            Port $port
            ListenAddress 127.0.0.1
            HostKey $base/host
            AuthorizedKeysFile $keys
            PidFile $base/pid
            StrictModes no
            UsePAM no
            PasswordAuthentication no
            Subsystem sftp /usr/lib/ssh/sftp-server
            """.trimIndent()
        )
        sshd = ProcessBuilder(server, "-D", "-e", "-f", cfg.path).redirectErrorStream(true).redirectOutput(base.resolve("log")).start()
        for (i in 0 until 50) {
            if (runCatching { java.net.Socket("127.0.0.1", port).close() }.isSuccess) break
            Thread.sleep(100)
        }
        config = ServerConfig(
            name = "t", type = StorageType.SFTP, host = "127.0.0.1", port = port,
            username = System.getProperty("user.name"), keyPath = "$base/user"
        )
        fs = SftpFileSystem(config, base.resolve("known_hosts"), prompts)
    }

    @After
    fun stop() {
        if (::fs.isInitialized) fs.close()
        if (::sshd.isInitialized) sshd.destroy()
        base.deleteRecursively()
    }

    private val dir get() = data.path

    @Test
    fun aNewKeyIsAskedOnceAndARefusalKeepsOut() {
        trust = false
        assertThrows(Exception::class.java) { fs.list(dir) }
        assertEquals(1, asked.size)
        assertTrue(asked[0].first, asked[0].first.startsWith("SHA256:"))
        assertFalse("first sight is not a change", asked[0].second)

        trust = true
        fs.list(dir)
        assertEquals(2, asked.size)
        fs.close()
        fs.list(dir)
        assertEquals("a known key is not asked about", 2, asked.size)
        assertEquals(asked[1].first, fs.fingerprint())
    }

    @Test
    fun aChangedKeyIsReportedAndRefusedOrAccepted() {
        fs.list(dir)
        fs.close()
        val known = base.resolve("known_hosts")
        val other = ProcessBuilder("ssh-keygen", "-q", "-t", "ed25519", "-N", "", "-f", "$base/other").start().apply { waitFor() }
        assertEquals(0, other.exitValue())
        val otherKey = base.resolve("other.pub").readText().trim().split(' ').take(2).joinToString(" ")
        known.writeText(known.readText().lines().filter { it.isNotBlank() }.joinToString("\n") { it.split(' ')[0] + " " + otherKey } + "\n")

        trust = false
        asked.clear()
        assertThrows(Exception::class.java) { SftpFileSystem(config, known, prompts).list(dir) }
        assertTrue(asked.single().second)
    }

    @Test
    fun readsFromAnOffsetAppendsAndReplaces() {
        val bytes = Random.nextBytes(300_000)
        val path = "$dir/f.bin"
        fs.openWrite(path).use { it.write(bytes, 0, 100_000) }
        fs.openAppend(path).use { it.write(bytes, 100_000, 200_000) }
        assertArrayEquals(bytes, File(path).readBytes())
        assertEquals(bytes.size.toLong(), fs.stat(path)?.size)
        assertArrayEquals(bytes.copyOfRange(250_000, 300_000), fs.openRead(path, 250_000).use { it.readBytes() })
        assertNull(fs.stat("$dir/missing"))

        File("$dir/old").writeText("old")
        fs.replace(path, "$dir/old")
        assertEquals(bytes.size.toLong(), File("$dir/old").length())
        assertFalse(File(path).exists())
    }

    @Test
    fun keepsModificationTimeAndGlobLikeNames() {
        val path = "$dir/a*b?.txt"
        fs.openWrite(path).use { it.write(1) }
        fs.setModified(path, 1_600_000_000_000)
        assertEquals(1_600_000_000_000, File(path).lastModified())
        assertEquals(1, fs.list(dir).count { it.name == "a*b?.txt" })
    }

    @Test
    fun runsCommandsAndCopiesOnTheServer() {
        val result = fs.exec("echo out; echo err >&2; exit 3")!!
        assertEquals(3, result.code)
        assertEquals("out\n", result.output)
        assertEquals("err\n", result.errors)

        File("$dir/src it's").apply { mkdir() }.resolve("in.txt").writeText("x")
        val source = FileItem(name = "src it's", path = "$dir/src it's", isDirectory = true)
        assertTrue(fs.copyWithin(source, "$dir/copy"))
        assertEquals("x", File("$dir/copy/in.txt").readText())
    }

    @Test
    fun reconnectsAfterTheSessionDied() {
        fs.list(dir)
        fs.close()
        assertFalse(fs.isOpen)
        fs.list(dir)
        assertTrue(fs.isOpen)
    }

    @Test
    fun replacingKeepsTheModeAndWritesThroughLinks() {
        val real = File("$dir/secret").apply { writeText("old") }
        Files.setPosixFilePermissions(real.toPath(), java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"))
        fs.openWrite("$dir/temp").use { it.write("new".toByteArray()) }
        fs.replaceKeeping("$dir/temp", real.path)
        assertEquals("new", real.readText())
        assertEquals(0b110000000, fs.stat(real.path)?.mode?.and(0x1ff))

        val link = java.nio.file.Paths.get("$dir/link")
        Files.createSymbolicLink(link, real.toPath())
        fs.openWrite("$dir/temp").use { it.write("via link".toByteArray()) }
        fs.replaceKeeping("$dir/temp", link.toString())
        assertTrue("the link stays a link", Files.isSymbolicLink(link))
        assertEquals("via link", real.readText())
        assertEquals("not widened by the link's own 777", 0b110000000, fs.stat(real.path)?.mode?.and(0x1ff))
        assertFalse(File("$dir/temp").exists())
    }

    @Test
    fun someoneElsesFileIsWrittenInPlaceWhenItsOwnerCannotBeKept() {
        // As after logging in as another user: the server refuses the chown.
        val real = File("$dir/theirs").apply { writeText("old") }
        val inode = Files.getAttribute(real.toPath(), "unix:ino")
        val notOwner = object : FileSystem by fs {
            override fun stat(path: String) = fs.stat(path)?.let { if (path == real.path) it.copy(uid = 12345) else it }
            override fun chown(path: String, uid: Int, gid: Int) = throw java.io.IOException("not permitted")
            override fun replaceKeeping(temp: String, path: String) = super<FileSystem>.replaceKeeping(temp, path)
        }
        fs.openWrite("$dir/temp").use { it.write("new".toByteArray()) }
        notOwner.replaceKeeping("$dir/temp", real.path)
        assertEquals("new", real.readText())
        assertEquals("the same file, so the same owner", inode, Files.getAttribute(real.toPath(), "unix:ino"))
        assertFalse(File("$dir/temp").exists())
    }
}
