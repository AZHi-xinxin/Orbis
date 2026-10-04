package me.rerere.rikkahub.data.orbis.privateroom

import java.io.Closeable
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/** Dependency-free, literal-loopback HTTP fixtures. Never connects to real models. */
internal class PrivateRoomLoopbackResponse {
    var body: String = "{}"
    var code: Int = 200
    val headers = linkedMapOf<String, String>()
    fun setBody(value: String) = apply { body = value }
    fun setResponseCode(value: Int) = apply { code = value }
    fun addHeader(name: String, value: String) = apply { headers[name] = value }
}

internal data class PrivateRoomLoopbackRequest(
    val method: String, val path: String, val headers: Map<String, String>, val body: String,
) {
    val bodySize: Long get() = body.toByteArray(Charsets.UTF_8).size.toLong()
    fun getHeader(name: String) = headers[name.lowercase()]
}

internal class PrivateRoomLoopbackServer : Closeable {
    private val socket = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
    private val responses = LinkedBlockingQueue<PrivateRoomLoopbackResponse>()
    private val requests = LinkedBlockingQueue<PrivateRoomLoopbackRequest>()
    private val count = AtomicInteger()
    private val failure = AtomicReference<Throwable?>()
    @Volatile private var closed = false
    private val worker = thread(name = "private-room-loopback", isDaemon = true) {
        try {
            while (!closed) socket.accept().use { connection ->
                connection.soTimeout = 5_000
                val input = connection.getInputStream()
                fun line(): String {
                    val bytes = ByteArrayOutputStream()
                    while (bytes.size() <= 16_384) {
                        val value = input.read()
                        if (value == -1 || value == 10) return bytes.toString("UTF-8").trimEnd('\r')
                        bytes.write(value)
                    }
                    error("fixture header too large")
                }
                val first = line().split(' ')
                check(first.size >= 2)
                val headers = linkedMapOf<String, String>()
                while (true) {
                    val header = line()
                    if (header.isEmpty()) break
                    val at = header.indexOf(':'); check(at > 0)
                    headers[header.substring(0, at).lowercase()] = header.substring(at + 1).trim()
                }
                val length = headers["content-length"]?.toInt() ?: 0
                check(length in 0..1_048_576)
                val body = ByteArray(length)
                var offset = 0
                while (offset < length) {
                    val read = input.read(body, offset, length - offset); check(read > 0); offset += read
                }
                requests.put(PrivateRoomLoopbackRequest(first[0], first[1], headers, body.toString(Charsets.UTF_8)))
                count.incrementAndGet()
                val response = responses.poll(5, TimeUnit.SECONDS) ?: error("fixture response missing")
                val bytes = response.body.toByteArray(Charsets.UTF_8)
                val head = buildString {
                    append("HTTP/1.1 ${response.code} Test\r\nContent-Type: application/json\r\n")
                    append("Content-Length: ${bytes.size}\r\nConnection: close\r\n")
                    response.headers.forEach { (k, v) -> append("$k: $v\r\n") }
                    append("\r\n")
                }
                try {
                    connection.getOutputStream().apply { write(head.toByteArray(Charsets.UTF_8)); write(bytes); flush() }
                } catch (_: SocketException) { /* bounded response tests intentionally close early */ }
            }
        } catch (error: Throwable) { if (!closed) failure.set(error) }
    }
    val requestCount: Int get() = count.get()
    fun url(path: String) = "http://127.0.0.1:${socket.localPort}$path"
    fun enqueue(response: PrivateRoomLoopbackResponse) { responses.put(response) }
    fun takeRequest(): PrivateRoomLoopbackRequest = requests.poll(5, TimeUnit.SECONDS)
        ?: throw AssertionError("fixture request missing", failure.get())
    override fun close() {
        closed = true; socket.close(); worker.join(6_000)
        check(!worker.isAlive) { "fixture did not stop" }
        failure.get()?.let { throw AssertionError("loopback fixture failed", it) }
    }
}
