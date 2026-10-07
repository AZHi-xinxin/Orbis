package me.rerere.ai.util

import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test

class OrbisGatewayThreadIdleCancellationTest {
    @Test(timeout = 12_000) fun twoSecondIdleBudgetCancelsActualStalledHttpBody(): Unit = runBlocking<Unit> {
        val server = ServerSocket().apply {
            bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0))
            soTimeout = 4_000
        }
        val headersSent = CountDownLatch(1)
        val socketClosed = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val worker = thread(name = "synthetic-thread-status-stall", isDaemon = true) {
            try {
                server.accept().use { socket ->
                    check(socket.inetAddress.isLoopbackAddress)
                    socket.soTimeout = 4_000
                    val reader = socket.getInputStream().bufferedReader()
                    check(reader.readLine() == "POST /v1/turns/thread-status HTTP/1.1")
                    var length = 0
                    while (true) {
                        val line = checkNotNull(reader.readLine())
                        if (line.isEmpty()) break
                        if (line.startsWith("Content-Length:", ignoreCase = true)) length = line.substringAfter(':').trim().toInt()
                    }
                    check(length in 1..2_048)
                    repeat(length) { check(reader.read() >= 0) }
                    socket.getOutputStream().apply {
                        write(("HTTP/1.1 200 Synthetic\r\nContent-Type: application/json\r\n" +
                            "Cache-Control: no-store\r\nContent-Length: 1000\r\nConnection: close\r\n\r\n{").toByteArray())
                        flush()
                    }
                    headersSent.countDown()
                    check(reader.read() == -1)
                    socketClosed.countDown()
                }
            } catch (error: Throwable) { failure.set(error); headersSent.countDown() }
        }
        val client = OkHttpClient.Builder().proxy(Proxy.NO_PROXY).retryOnConnectionFailure(false).build()
        val provider = ProviderSetting.OpenAI(baseUrl = "http://127.0.0.1:${server.localPort}/v1", apiKey = "synthetic-key")
        try {
            val control = OrbisGatewayThreadControl(client)
            val before = System.nanoTime()
            val result = async(start = CoroutineStart.UNDISPATCHED) {
                // This is the same budget as the host's disposable-frame readonly proof.
                withTimeoutOrNull(2_000) { control.probe(provider, Model(modelId = "synthetic-model"), "synthetic-conversation") }
            }
            assertTrue(headersSent.await(3, TimeUnit.SECONDS))
            assertNull(result.await())
            assertTrue(socketClosed.await(2, TimeUnit.SECONDS))
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - before) < 5_000)
            failure.get()?.let { throw AssertionError("Synthetic fixture failed", it) }
        } finally {
            server.close()
            worker.join(1_000)
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdownNow()
        }
    }
}
