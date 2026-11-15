package me.rerere.rikkahub.data.orbis.plugins
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.Collections
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
internal class PluginLoopbackServer(private val responses: List<String>) : AutoCloseable {
        private val server = ServerSocket().apply { bind(InetSocketAddress("127.0.0.1", 0)); soTimeout = 15000 }
        val url = "http://127.0.0.1:${server.localPort}"
        val requests = Collections.synchronizedList(mutableListOf<String>())
        @Volatile private var failure: Throwable? = null
        private val worker = thread(isDaemon = true) {
            try { responses.forEach { response -> server.accept().use { socket ->
                socket.soTimeout = 10000
                val input = BufferedInputStream(socket.getInputStream())
                fun line(): String {
                    val bytes = ByteArrayOutputStream()
                    while (true) { val ch = input.read(); check(ch >= 0 && bytes.size() < 16384); if (ch == 10) break; bytes.write(ch) }
                    return bytes.toString("UTF-8").trimEnd('\r')
                }
                check(line().startsWith("POST "))
                val headers = mutableMapOf<String, String>()
                while (true) { val line = line(); if (line.isEmpty()) break; headers[line.substringBefore(':').lowercase()] = line.substringAfter(':').trim() }
                val length = headers.getValue("content-length").toInt(); check(length in 1..131072)
                val bytes = ByteArray(length); var count = 0
                while (count < length) { val read = input.read(bytes, count, length - count); check(read > 0); count += read }
                requests.add(bytes.toString(Charsets.UTF_8))
                val body = response.toByteArray()
                socket.getOutputStream().apply {
                    write("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
                    write(body); flush()
                }
            } } } catch (error: Throwable) { failure = error }
        }
        fun verify() { worker.join(1000); failure?.let { throw AssertionError(it) }; assertEquals(responses.size, requests.size) }
        override fun close() { server.close(); worker.join(1000) }
    }
