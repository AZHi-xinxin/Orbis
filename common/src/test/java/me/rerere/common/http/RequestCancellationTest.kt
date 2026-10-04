package me.rerere.common.http

import java.io.IOException
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
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.Timeout
import okio.buffer
import org.junit.Assert.*
import org.junit.Test

class RequestCancellationTest {
    // Delegate version-specific tags/listeners only; execute/enqueue below never use the network.
    private class FakeCall : Call by OkHttpClient().newCall(
        Request.Builder().url("http://127.0.0.1:1/synthetic").build(),
    ) {
        var callback: Callback? = null
        var cancels = 0
        override fun request(): Request = Request.Builder().url("http://127.0.0.1:1/synthetic").build()
        override fun execute(): Response = error("No synchronous transport")
        override fun enqueue(responseCallback: Callback) { check(callback == null); callback = responseCallback }
        override fun cancel() { cancels++ }
        override fun isExecuted() = callback != null
        override fun isCanceled() = cancels > 0
        override fun timeout() = Timeout.NONE
        override fun clone(): Call = FakeCall()
    }

    private class TrackingBody : ResponseBody() {
        var closed = false
        private val input = object : ForwardingSource(Buffer().writeUtf8("synthetic")) {
            override fun close() { closed = true; super.close() }
        }.buffer()
        override fun contentType(): MediaType? = null
        override fun contentLength() = 9L
        override fun source(): BufferedSource = input
    }

    private fun response(call: Call, body: ResponseBody): Response = Response.Builder().request(call.request())
        .protocol(Protocol.HTTP_1_1).code(200).message("synthetic").body(body).build()

    @Test fun cancellingHeaderWaitCancelsCallAndClosesLateResponse() = runBlocking {
        val call = FakeCall()
        val waiting = async(start = CoroutineStart.UNDISPATCHED) { call.await() }
        waiting.cancelAndJoin()
        assertEquals(1, call.cancels)
        val body = TrackingBody()
        checkNotNull(call.callback).onResponse(call, response(call, body))
        assertTrue(body.closed)
        checkNotNull(call.callback).onFailure(call, IOException("late cancellation callback"))
        assertEquals(1, call.cancels)
    }

    @Test fun cancelledBodyWaitDoesNotParseLateResponseAndAlwaysClosesIt() = runBlocking {
        val call = FakeCall()
        var parsed = false
        val waiting = async(start = CoroutineStart.UNDISPATCHED) { call.awaitAndUse { parsed = true; it.body.string() } }
        waiting.cancelAndJoin()
        val body = TrackingBody()
        checkNotNull(call.callback).onResponse(call, response(call, body))
        assertFalse(parsed); assertTrue(body.closed); assertEquals(1, call.cancels)
    }

    @Test fun successfulBodyParsingClosesResponseWithoutCancellingCompletedCall() = runBlocking {
        val call = FakeCall()
        val waiting = async(start = CoroutineStart.UNDISPATCHED) { call.awaitAndUse { it.body.string() } }
        val body = TrackingBody()
        checkNotNull(call.callback).onResponse(call, response(call, body))
        assertEquals("synthetic", waiting.await())
        assertTrue(body.closed); assertEquals(0, call.cancels)
    }

    @Test fun failedBodyParserClosesResponseAndPreservesFailure() = runBlocking {
        val call = FakeCall()
        val waiting = async(start = CoroutineStart.UNDISPATCHED) {
            runCatching { call.awaitAndUse { error("synthetic parser failure") } }
        }
        val body = TrackingBody()
        checkNotNull(call.callback).onResponse(call, response(call, body))
        assertEquals("synthetic parser failure", waiting.await().exceptionOrNull()?.message)
        assertTrue(body.closed)
    }

    @Test(timeout = 10_000) fun realHeaderWaitCancellationClosesSocketBeforeTransportTimeout() = stalled(false)
    @Test(timeout = 10_000) fun realBodyWaitCancellationClosesSocketBeforeTransportTimeout() = stalled(true)

    private fun stalled(sendHeaders: Boolean): Unit = runBlocking<Unit> {
        val server = ServerSocket().apply { bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0)); soTimeout = 3_000 }
        val connected = CountDownLatch(1)
        val ended = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val worker = thread(name = "synthetic-cancel-${server.localPort}", isDaemon = true) {
            try {
                server.accept().use { socket ->
                    check(socket.inetAddress.isLoopbackAddress)
                    socket.soTimeout = 3_000
                    val reader = socket.getInputStream().bufferedReader()
                    check(reader.readLine().startsWith("GET /synthetic "))
                    while (checkNotNull(reader.readLine()).isNotEmpty()) Unit
                    if (sendHeaders) socket.getOutputStream().apply {
                        write("HTTP/1.1 200 Synthetic\r\nContent-Length: 100\r\nConnection: close\r\n\r\n".toByteArray())
                        flush()
                    }
                    connected.countDown()
                    check(reader.read() == -1) // Client cancellation, not the 5 second call timeout.
                    ended.countDown()
                }
            } catch (error: Throwable) { failure.set(error); connected.countDown() }
        }
        val client = OkHttpClient.Builder().proxy(Proxy.NO_PROXY).retryOnConnectionFailure(false)
            .callTimeout(5, TimeUnit.SECONDS).build()
        val call = client.newCall(Request.Builder().url("http://127.0.0.1:${server.localPort}/synthetic").build())
        try {
            val waiting = async(start = CoroutineStart.UNDISPATCHED) {
                if (sendHeaders) call.awaitAndUse { it.body.string() } else call.await().use { "unused" }
            }
            assertTrue(connected.await(2, TimeUnit.SECONDS))
            val before = System.nanoTime()
            withTimeout(1_000) { waiting.cancelAndJoin() }
            assertTrue(call.isCanceled())
            assertTrue("Cancelled socket remained open", ended.await(1, TimeUnit.SECONDS))
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - before) < 2_000)
            failure.get()?.let { throw AssertionError("Synthetic server failed", it) }
        } finally {
            call.cancel(); server.close(); worker.join(1_000)
            client.connectionPool.evictAll(); client.dispatcher.executorService.shutdownNow()
        }
    }
}
