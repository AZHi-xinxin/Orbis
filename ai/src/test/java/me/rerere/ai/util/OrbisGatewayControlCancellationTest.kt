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
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.TextGenerationParams
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.*
import org.junit.Test

class OrbisGatewayControlCancellationTest {
    @Test(timeout = 10_000) fun outerDeadlineCancelsControlBodyReadInsteadOfWaitingTenSeconds(): Unit = runBlocking<Unit> {
        val server = ServerSocket().apply { bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0)); soTimeout = 3_000 }
        val headersSent = CountDownLatch(1)
        val socketClosed = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val worker = thread(name = "synthetic-control-stall-${server.localPort}", isDaemon = true) {
            try {
                server.accept().use { socket ->
                    check(socket.inetAddress.isLoopbackAddress)
                    socket.soTimeout = 3_000
                    val reader = socket.getInputStream().bufferedReader()
                    check(reader.readLine() == "POST /v1/turns/status HTTP/1.1")
                    var length = 0
                    while (true) {
                        val line = checkNotNull(reader.readLine())
                        if (line.isEmpty()) break
                        if (line.startsWith("Content-Length:", ignoreCase = true)) length = line.substringAfter(':').trim().toInt()
                    }
                    check(length in 1..2_048)
                    repeat(length) { check(reader.read() >= 0) }
                    socket.getOutputStream().apply {
                        write("HTTP/1.1 200 Synthetic\r\nContent-Type: application/json\r\nContent-Length: 100\r\nConnection: close\r\n\r\n".toByteArray())
                        flush()
                    }
                    headersSent.countDown()
                    check(reader.read() == -1)
                    socketClosed.countDown()
                }
            } catch (error: Throwable) { failure.set(error); headersSent.countDown() }
        }
        val client = OkHttpClient.Builder().proxy(Proxy.NO_PROXY).retryOnConnectionFailure(false).build()
        val params = TextGenerationParams(Model(modelId = "synthetic-model"), orbisConversationId = "synthetic", onGatewayRequest = {})
        val request = Request.Builder().url("http://127.0.0.1:${server.localPort}/v1/chat/completions")
            .headers(params.orbisSourceHeaders()).header("Authorization", "Bearer synthetic-key")
            .post("{}".toRequestBody()).build().observeOrbisGatewayRequest(params, "synthetic-model")
        try {
            val control = OrbisGatewayTurnControl(client)
            val before = System.nanoTime()
            val result = async(start = CoroutineStart.UNDISPATCHED) {
                runCatching { withTimeout(600) { control.status(checkNotNull(request.tag(OrbisGatewayRequest::class.java))) } }
            }
            assertTrue(headersSent.await(2, TimeUnit.SECONDS))
            assertTrue(result.await().exceptionOrNull() is TimeoutCancellationException)
            assertTrue(socketClosed.await(1, TimeUnit.SECONDS))
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - before) < 3_000)
            failure.get()?.let { throw AssertionError("Synthetic fixture failed", it) }
        } finally {
            server.close(); worker.join(1_000)
            client.connectionPool.evictAll(); client.dispatcher.executorService.shutdownNow()
        }
    }
}
