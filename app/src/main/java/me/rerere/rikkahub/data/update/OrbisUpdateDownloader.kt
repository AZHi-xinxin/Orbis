package me.rerere.rikkahub.data.update

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.rerere.common.http.await
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

enum class OrbisDownloadPhase { CONNECTING, DOWNLOADING, RETRYING, VERIFYING, READY }

data class OrbisDownloadProgress(
    val downloaded: Long, val total: Long, val bytesPerSecond: Long = 0,
    val secondsRemaining: Long? = null, val phase: OrbisDownloadPhase = OrbisDownloadPhase.CONNECTING,
    val detail: String = "正在连接下载源",
)

internal data class OrbisDownloadSource(val label: String, val url: String, val allows: (String) -> Boolean)

/** Public bytes only. Every retry resolves a fresh URL (signed CDN redirects may expire). */
internal fun orbisDownloadSources(asset: OrbisReleaseAsset): List<OrbisDownloadSource> = buildList {
    add(OrbisDownloadSource("GitHub", asset.downloadUrl, ::orbisTrustedDownloadRedirect))
    if (asset.id > 0) {
        val api = "https://api.github.com/repos/AZHi-xinxin/Orbis/releases/assets/${asset.id}"
        add(OrbisDownloadSource("GitHub 备用入口", api) { it == api || orbisTrustedDownloadRedirect(it) })
    }
}

private class RangeNotSupported : IOException()
private class InvalidDownload(message: String) : IOException(message)

/** Resumable, bounded parallel range transfer; private partial files are never installation candidates. */
internal class OrbisUpdateDownloader(
    private val directory: File,
    private val client: OkHttpClient = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build(),
    private val partSize: Long = 8L * 1024 * 1024,
    private val parallelism: Int = 4,
    private val retryDelayMs: Long = 1_000,
    private val reserveBytes: Long = 64L * 1024 * 1024,
) {
    companion object {
        // All dialogs/service instances share the writer lock, including cancellation and final verification.
        private val downloadMutex = Mutex()
    }

    suspend fun discard(sha: String, size: Long) = withContext(Dispatchers.IO) {
        downloadMutex.withLock {
            paths(sha, size).let { (parts, apk) -> parts.deleteRecursively(); apk.delete() }
        }
    }

    suspend fun download(sha: String, size: Long, sources: List<OrbisDownloadSource>,
        progress: (OrbisDownloadProgress) -> Unit = {}): File = withContext(Dispatchers.IO) {
        downloadMutex.withLock {
            require(sources.isNotEmpty() && partSize > 0 && parallelism in 1..4)
            check(directory.isDirectory || directory.mkdirs()) { "无法创建更新缓存" }
            val (parts, apk) = paths(sha, size)
            if (apk.isFile && apk.length() == size) {
                progress(OrbisDownloadProgress(size, size, phase = OrbisDownloadPhase.VERIFYING, detail = "核对已下载的安装包"))
                if (hash(apk) == sha) {
                    progress(OrbisDownloadProgress(size, size, phase = OrbisDownloadPhase.READY, detail = "下载完成"))
                    return@withLock apk
                }
            }
            apk.delete()
            check(parts.isDirectory || parts.mkdirs()) { "无法保存下载进度" }
            // Assembly temporarily needs both the parts and one complete APK.
            check(directory.usableSpace >= size + reserveBytes) { "手机剩余空间不足，请腾出空间后继续；下载进度已保留" }
            val count = ((size + partSize - 1) / partSize).toInt()
            val lengths = LongArray(count) { i -> minOf(partSize, size - i * partSize) }
            val files = Array(count) { File(parts, "$it.part") }
            val single = File(parts, "single.part")
            val singleMode = File(parts, "single-mode")
            fun saved(file: File, limit: Long): Long {
                if (file.length() > limit) file.delete()
                return file.length()
            }
            val usedFiles: List<File>
            if (!singleMode.exists()) {
                val meter = DownloadMeter(size, LongArray(count) { saved(files[it], lengths[it]) }, progress)
                try {
                    withMeter(meter) {
                        val next = AtomicInteger()
                        coroutineScope {
                            List(minOf(parallelism, count)) { async {
                                while (true) {
                                    val i = next.getAndIncrement()
                                    if (i >= count) break
                                    receive(files[i], i * partSize, lengths[i], size, sources, true, meter, i)
                                }
                            } }.awaitAll()
                        }
                    }
                } catch (_: RangeNotSupported) {
                    // All range workers have joined before changing layout. Keep their parts until completion.
                    singleMode.writeText("1")
                }
            }
            if (singleMode.exists()) {
                val meter = DownloadMeter(size, longArrayOf(saved(single, size)), progress)
                withMeter(meter) { receive(single, 0, size, size, sources, false, meter, 0) }
                usedFiles = listOf(single)
            } else usedFiles = files.toList()

            check(directory.usableSpace >= size + reserveBytes) { "合并安装包需要更多空间，下载进度已保留" }
            progress(OrbisDownloadProgress(size, size, phase = OrbisDownloadPhase.VERIFYING, detail = "下载完成，正在校验文件"))
            try {
                val digest = MessageDigest.getInstance("SHA-256")
                var copied = 0L
                apk.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    usedFiles.forEach { file -> file.inputStream().use { input ->
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val n = input.read(buffer)
                            if (n < 0) break
                            copied += n
                            check(copied <= size) { "下载文件长度异常" }
                            output.write(buffer, 0, n); digest.update(buffer, 0, n)
                        }
                    } }
                }
                if (copied != size || digest.digest().hex() != sha) {
                    parts.deleteRecursively()
                    throw InvalidDownload("下载文件校验不一致，请重新下载")
                }
                parts.deleteRecursively()
                progress(OrbisDownloadProgress(size, size, phase = OrbisDownloadPhase.READY, detail = "下载完成"))
                apk
            } catch (failure: Throwable) {
                apk.delete() // Partial chunks survive cancellation; only a verified complete APK survives here.
                throw failure
            }
        }
    }

    private suspend fun withMeter(meter: DownloadMeter, block: suspend () -> Unit) = coroutineScope {
        meter.emit()
        val ticker = launch { while (isActive) { delay(1_000); meter.emit() } }
        try { block() } finally { ticker.cancelAndJoin() }
    }

    private suspend fun receive(file: File, start: Long, length: Long, total: Long,
        sources: List<OrbisDownloadSource>, rangeRequired: Boolean, meter: DownloadMeter, index: Int) {
        var failures = 0
        while (file.length() < length) {
            currentCoroutineContext().ensureActive()
            val source = sources[failures % sources.size]
            try {
                transfer(file, start, length, total, source, rangeRequired, meter, index)
                if (file.length() < length) throw EOFException("下载连接提前结束")
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (unsupported: RangeNotSupported) { throw unsupported }
            catch (invalid: InvalidDownload) { file.delete(); throw invalid }
            catch (failure: IOException) {
                currentCoroutineContext().ensureActive()
                if (++failures >= 4) throw IOException("下载连接暂时不可用，进度已保留；点“继续下载”重试", failure)
                meter.note(OrbisDownloadPhase.RETRYING, "连接中断，保留进度重试（$failures/3）")
                delay(retryDelayMs * failures)
            }
        }
    }

    private suspend fun transfer(file: File, start: Long, length: Long, total: Long,
        source: OrbisDownloadSource, rangeRequired: Boolean, meter: DownloadMeter, index: Int) {
        var offset = file.length()
        val end = start + length - 1
        var url = source.url
        repeat(6) {
            if (!source.allows(url)) throw InvalidDownload("下载地址不属于所选来源")
            val request = Request.Builder().url(url).header("Accept", "application/octet-stream")
                .header("Accept-Encoding", "identity").header("User-Agent", "Orbis-Updater")
                .header("Range", "bytes=${start + offset}-$end").build()
            val call = client.newCall(request)
            val next = coroutineScope {
                // Keep cancellation wired through the body read, and join the writer before releasing the lock.
                val canceller = launch(start = CoroutineStart.UNDISPATCHED) {
                    try { awaitCancellation() } finally { call.cancel() }
                }
                try {
                    call.await().use { response ->
                        if (response.code in setOf(301, 302, 303, 307, 308)) {
                            response.header("Location") ?: throw InvalidDownload("下载重定向缺少地址")
                        } else {
                            if (response.code == 200 && rangeRequired) throw RangeNotSupported()
                            if (response.code !in setOf(200, 206)) throw IOException("下载响应 HTTP ${response.code}")
                            if (response.header("Content-Encoding")?.let { !it.equals("identity", true) } == true)
                                throw InvalidDownload("下载编码与分段请求不匹配")
                            if (response.code == 206) {
                                val range = Regex("bytes (\\d+)-(\\d+)/(\\d+)").matchEntire(response.header("Content-Range").orEmpty())
                                    ?: throw InvalidDownload("下载分段响应缺少有效范围")
                                val numbers = range.groupValues.drop(1).map { it.toLongOrNull() }
                                if (numbers != listOf(start + offset, end, total)) throw InvalidDownload("下载分段范围不匹配")
                            } else offset = 0 // A server ignoring Range requires restarting only this single stream.
                            val expected = length - offset
                            if (response.body.contentLength().let { it >= 0 && it != expected })
                                throw InvalidDownload("下载分段长度不匹配")
                            meter.note(OrbisDownloadPhase.DOWNLOADING, "${source.label} · ${if (rangeRequired) "分段下载" else "连续下载"}")
                            meter.set(index, offset)
                            file.outputStreamForAppend(offset > 0).use { output -> response.body.byteStream().use { input ->
                                val buffer = ByteArray(64 * 1024)
                                while (true) {
                                    currentCoroutineContext().ensureActive()
                                    val n = input.read(buffer)
                                    if (n < 0) break
                                    if (offset + n > length) throw InvalidDownload("下载分段超出预期大小")
                                    if (directory.usableSpace < reserveBytes + n) throw IllegalStateException("空间不足，下载进度已保留")
                                    output.write(buffer, 0, n); offset += n; meter.set(index, offset)
                                }
                            } }
                            null
                        }
                    }
                } finally { withContext(NonCancellable) { canceller.cancelAndJoin() } }
            }
            if (next == null) return
            url = next
        }
        throw InvalidDownload("下载重定向次数异常")
    }

    private fun paths(sha: String, size: Long): Pair<File, File> {
        require(Regex("[0-9a-f]{64}").matches(sha) && size in 1..ORBIS_MAX_APK_BYTES)
        return File(directory, "$sha-$size.parts") to File(directory, "orbis-$sha.apk")
    }

    private suspend fun hash(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                currentCoroutineContext().ensureActive()
                val n = input.read(buffer); if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().hex()
    }
}

private fun File.outputStreamForAppend(append: Boolean) = java.io.FileOutputStream(this, append)
private fun ByteArray.hex() = joinToString("") { "%02x".format(it.toInt() and 255) }

private class DownloadMeter(private val total: Long, private val parts: LongArray,
    private val callback: (OrbisDownloadProgress) -> Unit) {
    private val samples = ArrayDeque<Pair<Long, Long>>()
    private var lastEmit = 0L
    private var phase = OrbisDownloadPhase.CONNECTING
    private var detail = "正在连接下载源，已有进度会自动接续"
    init { samples.add(System.nanoTime() to parts.sum()) }
    @Synchronized fun set(index: Int, value: Long) { parts[index] = value; emit() }
    @Synchronized fun note(value: OrbisDownloadPhase, text: String) { phase = value; detail = text; emit() }
    @Synchronized fun emit() {
        val now = System.nanoTime()
        if (lastEmit != 0L && now - lastEmit < 250_000_000L) return
        lastEmit = now
        val bytes = parts.sum()
        samples.add(now to bytes)
        while (samples.size > 2 && now - samples.first().first > 8_000_000_000L) samples.removeFirst()
        val elapsed = now - samples.first().first
        val speed = if (elapsed >= 500_000_000L) ((bytes - samples.first().second).coerceAtLeast(0) * 1_000_000_000.0 / elapsed).toLong() else 0
        callback(OrbisDownloadProgress(bytes, total, speed,
            if (speed > 0) (total - bytes + speed - 1) / speed else null, phase, detail))
    }
}

fun orbisDownloadStatus(progress: OrbisDownloadProgress): String {
    if (progress.phase == OrbisDownloadPhase.VERIFYING || progress.phase == OrbisDownloadPhase.READY) return progress.detail
    val speed = when {
        progress.bytesPerSecond >= 1024 * 1024 -> String.format(Locale.ROOT, "%.1f MiB/s", progress.bytesPerSecond / 1048576.0)
        progress.bytesPerSecond > 0 -> "${progress.bytesPerSecond / 1024} KiB/s"
        else -> "正在等待数据"
    }
    val eta = progress.secondsRemaining?.let {
        when { it >= 3600 -> "约 ${it / 3600} 小时 ${(it % 3600) / 60} 分钟"
            it >= 60 -> "约 ${(it + 59) / 60} 分钟"; else -> "约 $it 秒" }
    }
    return "$speed${eta?.let { " · 剩余$it" }.orEmpty()}\n${progress.detail}"
}
