package me.rerere.rikkahub.ui.pages.orbis

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.*
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.Density
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.update.*
import me.rerere.rikkahub.testutil.createShellComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.security.MessageDigest
import java.util.Collections
import java.util.concurrent.Executors

/** Isolated app, synthetic payload and local sockets. No release installation or user's download. */
class OrbisUpdateDownloadDeviceTest {
    @get:Rule val compose = createShellComposeRule()
    private fun screenshot(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        checkNotNull(instrumentation.uiAutomation.takeScreenshot()).let { bitmap ->
            File(context.getExternalFilesDir(null), name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    @Test fun largeFontShowsProgressSpeedAndPauseWithoutClipping() {
        var paused = false
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.6f)) {
                MaterialTheme { AlertDialog(onDismissRequest = { paused = true }, title = { Text("更新 Orbis") }, text = {
                    Column { OrbisDownloadProgressView(OrbisDownloadProgress(80L * 1048576, 241L * 1048576,
                        2L * 1048576, 81, OrbisDownloadPhase.DOWNLOADING, "GitHub · 分段下载")) }
                }, confirmButton = { TextButton(onClick = { paused = true }) { Text("暂停并关闭") } }) }
            }
        }
        compose.onNodeWithText("已下载 80 / 241 MiB").assertIsDisplayed()
        compose.onNodeWithText("2.0 MiB/s", substring = true).assertIsDisplayed()
        compose.onNodeWithText("剩余约 2 分钟", substring = true).assertIsDisplayed()
        screenshot("update-progress-large.png")
        compose.onNodeWithText("暂停并关闭").assertIsDisplayed().performClick()
        assertTrue(paused)
    }

    @Test fun verificationExplainsWhyFullProgressIsStillBusy() {
        compose.setContent { MaterialTheme(colorScheme = darkColorScheme()) { Surface { Column {
            OrbisDownloadProgressView(OrbisDownloadProgress(241L * 1048576, 241L * 1048576,
                phase = OrbisDownloadPhase.VERIFYING, detail = "下载完成，正在校验文件"))
        } } } }
        compose.onNodeWithText("下载完成，正在校验文件").assertIsDisplayed()
        compose.onNodeWithText("已下载 241 / 241 MiB").assertIsDisplayed()
        screenshot("update-verifying-dark.png")
    }

    @Test fun androidFilesystemAndRealHttpResumeExistingSegments() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName.endsWith(".bodylab"))
        val dir = File(context.cacheDir, "synthetic-update-${System.nanoTime()}").apply { mkdirs() }
        val data = ByteArray(128 * 1024) { ((it + 7) % 251).toByte() }
        val sha = MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it.toInt() and 255) }
        val parts = File(dir, "$sha-${data.size}.parts").apply { mkdirs() }
        File(parts, "0.part").writeBytes(data.copyOfRange(0, 4096))
        File(parts, "1.part").writeBytes(data.copyOfRange(32768, 65536))
        val requests = Collections.synchronizedList(mutableListOf<String>())
        val pool = Executors.newCachedThreadPool()
        val listener = ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"))
        pool.submit {
            while (!listener.isClosed) {
                val socket = try { listener.accept() } catch (_: Exception) { break }
                pool.submit { socket.use {
                    val reader = socket.getInputStream().bufferedReader()
                    val headers = mutableListOf<String>()
                    while (true) { val line = reader.readLine() ?: break; if (line.isEmpty()) break; headers += line }
                    val range = headers.first { it.startsWith("Range:", true) }.substringAfter(":").trim()
                    requests += range
                    val match = Regex("bytes=(\\d+)-(\\d+)").matchEntire(range)!!
                    val start = match.groupValues[1].toInt(); val end = match.groupValues[2].toInt()
                    val output = socket.getOutputStream()
                    output.write(("HTTP/1.1 206 Partial Content\r\nContent-Length: ${end - start + 1}\r\n" +
                        "Content-Range: bytes $start-$end/${data.size}\r\nConnection: close\r\n\r\n").toByteArray())
                    output.write(data, start, end - start + 1); output.flush()
                } }
            }
        }
        try {
            val url = "http://127.0.0.1:${listener.localPort}/synthetic.apk"
            val result = OrbisUpdateDownloader(dir, partSize = 32768, reserveBytes = 0)
                .download(sha, data.size.toLong(), listOf(OrbisDownloadSource("loopback", url) { it == url }))
            assertArrayEquals(data, result.readBytes())
            assertEquals(3, requests.size)
            assertTrue(requests.contains("bytes=4096-32767"))
            assertFalse(requests.any { it == "bytes=32768-65535" })
            assertFalse(parts.exists())
        } finally { listener.close(); pool.shutdownNow(); dir.deleteRecursively() }
    }
}
