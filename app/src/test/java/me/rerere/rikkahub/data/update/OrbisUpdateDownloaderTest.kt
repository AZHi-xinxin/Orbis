package me.rerere.rikkahub.data.update

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Real loopback HTTP, deterministic synthetic payloads; never accesses GitHub, APKs or phone data. */
class OrbisUpdateDownloaderTest {
    private val directory = Files.createTempDirectory("orbis-update-test").toFile()
    private val executor = Executors.newCachedThreadPool()
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        this.executor = this@OrbisUpdateDownloaderTest.executor; start()
    }
    private val base = "http://127.0.0.1:${server.address.port}"
    private val body = ByteArray(128 * 1024) { ((it * 31 + 17) % 251).toByte() }
    private val sha get() = sha(body)
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun source(path: String = "/asset") = OrbisDownloadSource(path, base + path) { it.startsWith("$base/") }
    private fun downloader(parts: Long = 32768, workers: Int = 4) = OrbisUpdateDownloader(directory,
        OkHttpClient.Builder().followRedirects(false).readTimeout(300, TimeUnit.MILLISECONDS).build(),
        partSize = parts, parallelism = workers, retryDelayMs = 1, reserveBytes = 0)
    private fun handler(path: String = "/asset", block: (HttpExchange) -> Unit) {
        server.createContext(path) { ex -> try { block(ex) } finally { ex.close() } }
    }
    private fun range(ex: HttpExchange): LongRange {
        val m = Regex("bytes=(\\d+)-(\\d+)").matchEntire(ex.requestHeaders.getFirst("Range"))!!
        return m.groupValues[1].toLong()..m.groupValues[2].toLong()
    }
    private fun respond(ex: HttpExchange, bytes: ByteArray = body, corrupt: Boolean = false) {
        val r = range(ex)
        ex.responseHeaders.add("Content-Range", "bytes ${r.first}-${r.last}/${bytes.size}")
        ex.sendResponseHeaders(206, r.last - r.first + 1)
        val selected = bytes.copyOfRange(r.first.toInt(), r.last.toInt() + 1)
        if (corrupt) selected[0] = (selected[0].toInt() xor 1).toByte()
        ex.responseBody.write(selected)
    }
    @After fun close() { server.stop(0); executor.shutdownNow(); directory.deleteRecursively() }

    @Test fun fourWorkersActuallyOverlapAndAssembleExactBytes() = runBlocking {
        val active = AtomicInteger(); val peak = AtomicInteger(); val requests = AtomicInteger()
        handler { ex ->
            requests.incrementAndGet(); val n = active.incrementAndGet(); peak.updateAndGet { maxOf(n, it) }
            try { Thread.sleep(80); respond(ex) } finally { active.decrementAndGet() }
        }
        val snapshots = CopyOnWriteArrayList<OrbisDownloadProgress>()
        val file = downloader().download(sha, body.size.toLong(), listOf(source())) { snapshots += it }
        assertArrayEquals(body, file.readBytes()); assertEquals(4, requests.get()); assertTrue(peak.get() >= 2)
        assertEquals(body.size.toLong(), snapshots.last().downloaded)
        assertEquals(OrbisDownloadPhase.READY, snapshots.last().phase)
        assertEquals(listOf(file), directory.listFiles()!!.toList())
        // Reopen after a process/service recreation revalidates and reuses the completed bytes.
        downloader().download(sha, body.size.toLong(), listOf(source()))
        assertEquals(4, requests.get())
    }

    @Test fun truncatedConnectionRetriesFromPersistedByteInsteadOfZero() = runBlocking {
        val ranges = CopyOnWriteArrayList<LongRange>()
        handler { ex ->
            val r = range(ex); ranges += r
            if (ranges.size == 1) {
                ex.responseHeaders.add("Content-Range", "bytes 0-${body.lastIndex}/${body.size}")
                ex.sendResponseHeaders(206, body.size.toLong())
                ex.responseBody.write(body, 0, 8192)
            } else respond(ex)
        }
        val file = downloader(body.size.toLong(), 1).download(sha, body.size.toLong(), listOf(source()))
        assertArrayEquals(body, file.readBytes()); assertTrue(ranges.size >= 2)
        assertEquals(8192L, ranges[1].first)
    }

    @Test fun stalledSocketCancellationJoinsWriterAndFreshDownloaderResumes() = runBlocking {
        val first = java.util.concurrent.CountDownLatch(1)
        val starts = CopyOnWriteArrayList<Long>()
        handler { ex ->
            val r = range(ex); starts += r.first
            if (starts.size == 1) {
                ex.responseHeaders.add("Content-Range", "bytes 0-${body.lastIndex}/${body.size}")
                ex.sendResponseHeaders(206, body.size.toLong()); ex.responseBody.write(body, 0, 4096)
                ex.responseBody.flush(); first.countDown(); Thread.sleep(5000)
            } else respond(ex)
        }
        val worker = launch(Dispatchers.IO) { downloader(body.size.toLong(), 1).download(sha, body.size.toLong(), listOf(source())) }
        withContext(Dispatchers.IO) { assertTrue(first.await(3, TimeUnit.SECONDS)) }
        delay(60)
        withTimeout(2000) { worker.cancelAndJoin() }
        val part = directory.walkTopDown().single { it.name == "0.part" }
        assertEquals(4096L, part.length()); assertFalse(directory.listFiles()!!.any { it.extension == "apk" })
        val file = downloader(body.size.toLong(), 1).download(sha, body.size.toLong(), listOf(source()))
        assertEquals(4096L, starts[1]); assertArrayEquals(body, file.readBytes())
    }

    @Test fun retriesAlternateOfficialEntriesAndStopAfterFourAttempts() = runBlocking {
        val firstCalls = AtomicInteger(); val backupCalls = AtomicInteger()
        handler { ex -> firstCalls.incrementAndGet(); ex.sendResponseHeaders(503, -1) }
        handler("/backup") { ex -> backupCalls.incrementAndGet(); respond(ex) }
        val file = downloader(body.size.toLong(), 1).download(sha, body.size.toLong(), listOf(source(), source("/backup")))
        assertArrayEquals(body, file.readBytes()); assertEquals(1, firstCalls.get()); assertEquals(1, backupCalls.get())
        downloader().discard(sha, body.size.toLong())
        try { downloader(body.size.toLong(), 1).download(sha, body.size.toLong(), listOf(source())); fail() }
        catch (e: IOException) { assertTrue(e.message!!.contains("进度已保留")) }
        assertEquals(5, firstCalls.get())
    }

    @Test fun serverIgnoringRangesFallsBackToOneFullStream() = runBlocking {
        handler { ex -> ex.sendResponseHeaders(200, body.size.toLong()); ex.responseBody.write(body) }
        val file = downloader().download(sha, body.size.toLong(), listOf(source()))
        assertArrayEquals(body, file.readBytes())
    }

    @Test fun singleStreamResumeGetting200TruncatesInsteadOfAppendingDuplicatePrefix() = runBlocking {
        val parts = File(directory, "$sha-${body.size}.parts").apply { mkdirs() }
        File(parts, "single-mode").writeText("1"); File(parts, "single.part").writeBytes(body.copyOf(4096))
        val starts = CopyOnWriteArrayList<Long>()
        handler { ex -> starts += range(ex).first; ex.sendResponseHeaders(200, body.size.toLong()); ex.responseBody.write(body) }
        assertArrayEquals(body, downloader().download(sha, body.size.toLong(), listOf(source())).readBytes())
        assertEquals(listOf(4096L), starts.toList())
    }

    @Test fun invalidContentRangeIsRejectedBeforeWritingAnyBytes() = runBlocking {
        handler { ex -> ex.responseHeaders.add("Content-Range", "bytes 1-${body.lastIndex}/${body.size}")
            ex.sendResponseHeaders(206, body.size.toLong()); ex.responseBody.write(body) }
        try { downloader(body.size.toLong(), 1).download(sha, body.size.toLong(), listOf(source())); fail() }
        catch (e: IOException) { assertTrue(e.message!!.contains("范围不匹配")) }
        assertEquals(0, directory.walkTopDown().filter { it.isFile }.count())
    }

    @Test fun wrongDigestDeletesCorruptPartSetAndNeverLeavesAnInstallableFile() = runBlocking {
        handler { respond(it, corrupt = true) }
        try { downloader().download(sha, body.size.toLong(), listOf(source())); fail() }
        catch (e: IOException) { assertTrue(e.message!!.contains("校验不一致")) }
        assertTrue(directory.listFiles()!!.isEmpty())
    }

    @Test fun changedAssetDigestCannotReuseOldProgressAndDiscardIsScopedToThisAsset() = runBlocking {
        val oldSha = "a".repeat(64)
        val old = File(directory, "$oldSha-${body.size}.parts").apply { mkdirs() }
        File(old, "0.part").writeBytes(byteArrayOf(3, 4))
        val unrelated = File(directory, "keep.txt").apply { writeText("keep") }
        handler { respond(it) }
        val file = downloader().download(sha, body.size.toLong(), listOf(source()))
        downloader().discard(sha, body.size.toLong())
        assertFalse(file.exists()); assertTrue(old.exists()); assertEquals("keep", unrelated.readText())
    }

    @Test fun redirectsMustStayOnExplicitSourceAndNeverFetchAnUnapprovedUrl() = runBlocking {
        val backupCalls = AtomicInteger()
        handler { ex -> ex.responseHeaders.add("Location", "$base/forbidden"); ex.sendResponseHeaders(302, -1) }
        handler("/forbidden") { ex -> backupCalls.incrementAndGet(); respond(ex) }
        val restricted = OrbisDownloadSource("restricted", "$base/asset") { it == "$base/asset" }
        try { downloader().download(sha, body.size.toLong(), listOf(restricted)); fail() }
        catch (e: IOException) { assertTrue(e.message!!.contains("不属于")) }
        assertEquals(0, backupCalls.get())
    }

    @Test fun retryReResolvesExpiredSignedRedirectRatherThanReusingIt() = runBlocking {
        val redirects = AtomicInteger()
        handler { ex -> ex.responseHeaders.add("Location", "$base/cdn${redirects.incrementAndGet()}"); ex.sendResponseHeaders(302, -1) }
        handler("/cdn1") { it.sendResponseHeaders(403, -1) }
        handler("/cdn2") { respond(it) }
        assertArrayEquals(body, downloader(body.size.toLong(), 1).download(sha, body.size.toLong(), listOf(source())).readBytes())
        assertEquals(2, redirects.get())
    }

    @Test fun apiFallbackIsDerivedOnlyFromPublishedAssetIdAndConstrainedToOfficialUrls() {
        val asset = OrbisReleaseAsset("Orbis.apk", 100, "$ORBIS_RELEASES_PAGE/download/v1/Orbis.apk", id = 123)
        val routes = orbisDownloadSources(asset)
        assertEquals(2, routes.size)
        assertEquals("https://api.github.com/repos/AZHi-xinxin/Orbis/releases/assets/123", routes[1].url)
        assertTrue(routes[1].allows(routes[1].url))
        assertFalse(routes[1].allows("https://api.github.com/repos/other/repo/releases/assets/123"))
        assertFalse(routes[1].allows("http://release-assets.githubusercontent.com/a"))
        assertEquals(1, orbisDownloadSources(asset.copy(id = 0)).size)
    }

    @Test fun progressExplainsRateEtaWaitingAndVerification() {
        assertTrue(orbisDownloadStatus(OrbisDownloadProgress(20, 100, 32768, 121)).contains("32 KiB/s · 剩余约 3 分钟"))
        assertTrue(orbisDownloadStatus(OrbisDownloadProgress(20, 100, 2097152, 3)).contains("2.0 MiB/s"))
        assertTrue(orbisDownloadStatus(OrbisDownloadProgress(20, 100)).contains("等待数据"))
        assertEquals("校验中", orbisDownloadStatus(OrbisDownloadProgress(100, 100, phase = OrbisDownloadPhase.VERIFYING, detail = "校验中")))
    }
}
