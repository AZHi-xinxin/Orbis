package me.rerere.workspace

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.GZIPOutputStream

/** All files are synthetic and temporary; the only network endpoint is loopback. */
@RunWith(AndroidJUnit4::class)
class RootfsInstallerDeviceTest {
    @Test(timeout = 30_000)
    fun rootfsInstallerDownloadsAndExtractsTarGzWithRealSymlink() {
        val cache = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir.canonicalFile
        check(cache.isDirectory || cache.mkdirs())
        val temporary = Files.createTempDirectory(cache.toPath(), "rootfs-installer-synthetic-").toFile()
        check(temporary.canonicalFile.parentFile == cache)
        try {
            val manager = WorkspaceManager(temporary)
            val archive = tarGz(
                TarEntry("bin/", type = '5'),
                TarEntry("bin/hello", content = "echo hello\n".toByteArray(), mode = 493),
                TarEntry("usr/bin/hello-link", type = '2', linkName = "../../bin/hello"),
            )
            LoopbackArchive(archive).use { server ->
                val stages = mutableListOf<RootfsInstallStage>()
                RootfsInstaller(manager).install("test-workspace", server.url) {
                    stages += it.stage
                }
                server.assertCompleted()

                val linuxDir = manager.linuxDir("test-workspace")
                val executable = File(linuxDir, "bin/hello")
                val link = File(linuxDir, "usr/bin/hello-link").toPath()
                // Same success assertions as the host integration test, using
                // real Android filesystem links, never a mocked/copied stand-in.
                assertEquals("echo hello\n", executable.readText())
                assertTrue(executable.canExecute())
                assertTrue(Files.isSymbolicLink(link))
                assertEquals("../../bin/hello", Files.readSymbolicLink(link).toString())
                assertEquals(executable.canonicalFile, link.toFile().canonicalFile)
                assertEquals("echo hello\n", link.toFile().readText())
                assertTrue(stages.contains(RootfsInstallStage.DOWNLOADING))
                assertTrue(stages.contains(RootfsInstallStage.EXTRACTING))
                assertEquals(RootfsInstallStage.INSTALLED, stages.last())
                assertFalse(File(manager.tempDir("test-workspace"), "rootfs.tar.gz").exists())
                assertFalse(File(manager.tempDir("test-workspace"), "rootfs-staging").exists())
            }
        } finally {
            // Files.walk does not follow symlinks. Delete only this freshly
            // created, validated cache subtree, never the link's target outside it.
            check(temporary.canonicalFile.parentFile == cache)
            Files.walk(temporary.toPath()).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
            }
        }
    }

    private class LoopbackArchive(private val bytes: ByteArray) : AutoCloseable {
        private val listener = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).apply {
            soTimeout = 10_000
        }
        private val accepted = AtomicReference<Socket?>()
        private val failure = AtomicReference<Throwable?>()
        val url = "http://127.0.0.1:${listener.localPort}/rootfs.tar.gz"
        private val worker = Thread({
            try {
                listener.accept().use { socket ->
                    accepted.set(socket)
                    socket.soTimeout = 5_000
                    val headers = readHeaders(socket.getInputStream())
                    check(headers.startsWith("GET /rootfs.tar.gz HTTP/1."))
                    socket.getOutputStream().use { output ->
                        output.write(("HTTP/1.1 200 OK\r\nContent-Type: application/gzip\r\n" +
                            "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n")
                            .toByteArray(Charsets.US_ASCII))
                        output.write(bytes)
                        output.flush()
                    }
                }
            } catch (error: Throwable) {
                failure.set(error)
            }
        }, "synthetic-rootfs-loopback").apply {
            isDaemon = true
            start()
        }

        fun assertCompleted() {
            worker.join(5_000)
            assertFalse("Synthetic server must finish within its timeout", worker.isAlive)
            failure.get()?.let { throw AssertionError("Synthetic loopback server failed", it) }
        }

        override fun close() {
            listener.close()
            accepted.get()?.close()
            worker.join(5_000)
            assertFalse("Synthetic server thread must not outlive the test", worker.isAlive)
        }

        private fun readHeaders(input: InputStream): String {
            val output = ByteArrayOutputStream()
            var suffix = 0
            repeat(8 * 1024) {
                val next = input.read()
                check(next >= 0) { "Incomplete synthetic HTTP request" }
                output.write(next)
                suffix = (suffix shl 8) or next
                if (suffix == 0x0d0a0d0a) return output.toString(Charsets.US_ASCII.name())
            }
            error("Synthetic HTTP request headers exceeded limit")
        }
    }

    private fun tarGz(vararg entries: TarEntry): ByteArray {
        val output = ByteArrayOutputStream()
        GZIPOutputStream(output).use { gzip ->
            entries.forEach { entry ->
                val header = ByteArray(512)
                fun field(offset: Int, length: Int, value: String) {
                    val bytes = value.toByteArray(Charsets.UTF_8)
                    // TAR's version field is exactly two bytes ("00"), with
                    // no trailing NUL; fixed-width fields may fill their width.
                    check(bytes.size <= length)
                    bytes.copyInto(header, offset)
                }
                fun octal(offset: Int, length: Int, value: Long) =
                    field(offset, length, value.toString(8).padStart(length - 1, '0'))
                field(0, 100, entry.name)
                octal(100, 8, entry.mode.toLong())
                octal(108, 8, 0)
                octal(116, 8, 0)
                octal(124, 12, entry.content.size.toLong())
                octal(136, 12, 0)
                header.fill(' '.code.toByte(), 148, 156)
                header[156] = entry.type.code.toByte()
                field(157, 100, entry.linkName)
                field(257, 6, "ustar")
                field(263, 2, "00")
                octal(148, 8, header.sumOf { it.toUByte().toInt() }.toLong())
                gzip.write(header)
                gzip.write(entry.content)
                gzip.write(ByteArray((512 - entry.content.size % 512) % 512))
            }
            gzip.write(ByteArray(1024))
        }
        return output.toByteArray()
    }

    private data class TarEntry(
        val name: String,
        val content: ByteArray = byteArrayOf(),
        val mode: Int = 420,
        val type: Char = '0',
        val linkName: String = "",
    )
}
