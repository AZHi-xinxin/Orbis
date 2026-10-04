package me.rerere.rikkahub.data.ai.mcp

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.sse.SSE
import io.ktor.serialization.kotlinx.json.json
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.StreamableHttpClientTransport
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import org.junit.AfterClass
import org.junit.Assert.*
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.BufferedInputStream
import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.Collections
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/** Real SDK + production transport builder. Only synthetic config files and loopback HTTP;
 * no SettingsStore, application, private database, real credential or external endpoint.
 * This tests serialization/save/readback + reconnect wire, not the Compose Save button. */
class McpStaticAuthorizationWireTest {
    companion object {
        private const val LOGGING_PROVIDER_PROPERTY = "slf4j.provider"
        private var previousLoggingProvider: String? = null

        @JvmStatic
        @BeforeClass
        fun useJvmLoggingProvider() {
            previousLoggingProvider = System.getProperty(LOGGING_PROVIDER_PROPERTY)
            // Ktor initializes SLF4J; the app's Android binding calls android.util.Log,
            // which is unavailable in this plain JVM fixture. Keep the actual HTTP/auth
            // transport, but select SLF4J's API-provided no-op logger for this test JVM.
            System.setProperty(
                LOGGING_PROVIDER_PROPERTY,
                org.slf4j.helpers.NOP_FallbackServiceProvider::class.java.name,
            )
        }

        @JvmStatic
        @AfterClass
        fun restoreJvmLoggingProperty() {
            previousLoggingProvider?.let { System.setProperty(LOGGING_PROVIDER_PROPERTY, it) }
                ?: System.clearProperty(LOGGING_PROVIDER_PROPERTY)
        }
    }

    @get:Rule val temporary = TemporaryFolder()
    private val json = Json { encodeDefaults = true }

    @Test(timeout = 30_000)
    fun `saved manual header survives initial connection and reconnect including get and delete`() = runBlocking {
        Fixture().use { fixture ->
            val original = config(fixture.url, "Authorization", "Bearer synthetic-manual-one")
            val saved = saveAndReload(original)
            assertEquals(original, saved)
            fixture.expectedAuthorization.set("Bearer synthetic-manual-one")
            connectAndList(fixture, saved)
            connectAndList(fixture, saveAndReload(saved))
            assertEquals(2, fixture.requests.count { it.rpcMethod == "initialize" })
            assertEquals(2, fixture.requests.count { it.rpcMethod == "tools/list" })
            assertEquals(2, fixture.requests.count { it.method == "GET" })
            assertEquals(2, fixture.requests.count { it.method == "DELETE" })
            fixture.requests.forEach { request ->
                assertEquals(listOf("Bearer synthetic-manual-one"), request.authorization)
                assertEquals("synthetic-extra", request.extraHeader)
            }
            fixture.assertHealthy()
        }
    }

    @Test(timeout = 30_000)
    fun `changed saved manual authorization takes effect on the next connection`() = runBlocking {
        Fixture().use { fixture ->
            for ((index, name) in listOf("Authorization", "authorization", "AUTHORIZATION").withIndex()) {
                val value = "Bearer synthetic-manual-$index"
                fixture.expectedAuthorization.set(value)
                val saved = saveAndReload(config(fixture.url, name, value))
                assertFalse(saved.shouldRefreshOAuth(System.currentTimeMillis()))
                val before = fixture.requests.size
                connectAndList(fixture, saved)
                fixture.requests.drop(before).forEach { assertEquals(listOf(value), it.authorization) }
            }
            assertEquals(3, fixture.requests.count { it.rpcMethod == "tools/list" })
            fixture.assertHealthy()
        }
    }

    @Test(timeout = 15_000)
    fun `rejected manual credential remains an authentication error not an oauth requirement`() = runBlocking {
        Fixture().use { fixture ->
            fixture.expectedAuthorization.set("Bearer synthetic-required")
            val saved = saveAndReload(config(fixture.url, "Authorization", "Bearer synthetic-wrong"))
            val failure = runCatching { connectAndList(fixture, saved) }.exceptionOrNull()
            assertNotNull(failure)
            assertTrue(mcpLooksUnauthorized(checkNotNull(failure)))
            assertFalse(needsMcpOAuthAuthorization(saved, failure) { error("Manual auth must not probe OAuth") })
            assertTrue(mcpConnectionError(saved, failure).message.contains("手动认证未通过"))
            assertEquals(1, fixture.requests.size)
            assertEquals(listOf("Bearer synthetic-wrong"), fixture.requests.single().authorization)
            fixture.assertHealthy()
        }
    }

    @Test(timeout = 15_000)
    fun `oauth only connection still sends the stored oauth credential`() = runBlocking {
        Fixture().use { fixture ->
            fixture.expectedAuthorization.set("Bearer synthetic-old-oauth")
            val original = config(fixture.url, "Authorization", "unused")
            val saved = saveAndReload(original.clone(commonOptions = original.commonOptions.copy(headers = emptyList())))
            connectAndList(fixture, saved)
            fixture.requests.forEach { assertEquals(listOf("Bearer synthetic-old-oauth"), it.authorization) }
            fixture.assertHealthy()
        }
    }

    private fun config(url: String, name: String, value: String): McpServerConfig =
        McpServerConfig.StreamableHTTPServer(
            url = url,
            commonOptions = McpCommonOptions(
                name = "synthetic", headers = listOf(name to value, "X-Synthetic-Probe" to "synthetic-extra"),
                oauth = McpOAuthState(enabled = true, accessToken = "synthetic-old-oauth",
                    refreshToken = "synthetic-expired-refresh", expiresAt = 1,
                    tokenEndpoint = "https://must-not-contact.invalid/token", clientId = "synthetic-client"),
            ),
        )

    private fun saveAndReload(config: McpServerConfig): McpServerConfig {
        val file = temporary.newFile()
        file.writeText(json.encodeToString<McpServerConfig>(config), Charsets.UTF_8)
        return json.decodeFromString<McpServerConfig>(file.readText(Charsets.UTF_8))
    }

    private suspend fun connectAndList(fixture: Fixture, config: McpServerConfig) {
        val previousGets = fixture.requests.count { it.method == "GET" }
        val client = Client(Implementation("synthetic-wire-only", "1.0"))
        val transport = createMcpTransport(config, fixture.httpClient) as StreamableHttpClientTransport
        try {
            withTimeout(5_000) {
                client.connect(transport)
                assertTrue(client.listTools().tools.isEmpty())
                while (fixture.requests.count { it.method == "GET" } == previousGets) delay(10)
                transport.terminateSession()
            }
        } finally {
            client.close()
        }
    }

    private data class Request(val method: String, val rpcMethod: String?, val authorization: List<String>, val extraHeader: String?)

    private class Fixture : Closeable {
        private val loopback = InetAddress.getByName("127.0.0.1")
        private val listener = ServerSocket().apply { bind(InetSocketAddress(loopback, 0)); soTimeout = 200 }
        val url = "http://127.0.0.1:${listener.localPort}/mcp"
        val expectedAuthorization = AtomicReference("")
        private val captured: MutableList<Request> = Collections.synchronizedList(mutableListOf())
        val requests: List<Request> get() = synchronized(captured) { captured.toList() }
        private val closed = AtomicBoolean()
        private val failure = AtomicReference<Throwable?>()
        private val active = AtomicReference<Socket?>()
        private val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(25)
        private val okHttp = OkHttpClient.Builder()
            .proxy(Proxy.NO_PROXY).followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
            .connectTimeout(2, TimeUnit.SECONDS).readTimeout(3, TimeUnit.SECONDS).callTimeout(5, TimeUnit.SECONDS)
            .dns { if (it != "127.0.0.1") throw IOException("Synthetic fixture disallows external DNS"); listOf(loopback) }
            .addInterceptor { chain ->
                val target = chain.request().url
                check(target.scheme == "http" && target.host == "127.0.0.1" && target.port == listener.localPort &&
                    target.encodedPath == "/mcp" && target.query == null) { "Synthetic fixture endpoint violation" }
                chain.proceed(chain.request())
            }.build()
        val httpClient = HttpClient(OkHttp) {
            engine { preconfigured = okHttp }
            install(ContentNegotiation) { json() }
            install(SSE)
        }
        private val worker = thread(name = "synthetic-mcp-auth-loopback", isDaemon = true) {
            try {
                while (!closed.get()) {
                    check(System.nanoTime() < deadline) { "Synthetic fixture deadline exceeded" }
                    val socket = try { listener.accept() } catch (_: SocketTimeoutException) { continue }
                    active.set(socket)
                    socket.use { handle(it) }
                    active.set(null)
                }
            } catch (error: Throwable) {
                if (!closed.get()) failure.set(error)
            }
        }

        private fun handle(socket: Socket) {
            check(socket.inetAddress.isLoopbackAddress)
            socket.soTimeout = 3_000
            val input = BufferedInputStream(socket.getInputStream())
            fun line(): String {
                val result = StringBuilder()
                while (true) {
                    val value = input.read()
                    check(value >= 0 && result.length < 16_384)
                    if (value == 10) return result.toString().removeSuffix("\r")
                    result.append(value.toChar())
                }
            }
            val requestLine = line().split(' ')
            check(requestLine.size == 3 && requestLine[1] == "/mcp")
            val headers = mutableMapOf<String, MutableList<String>>()
            var headerSize = 0
            while (true) {
                val value = line()
                if (value.isEmpty()) break
                headerSize += value.length
                check(headerSize < 32_768)
                val colon = value.indexOf(':')
                check(colon > 0)
                headers.getOrPut(value.take(colon).lowercase()) { mutableListOf() }.add(value.drop(colon + 1).trim())
            }
            check("transfer-encoding" !in headers)
            val size = headers["content-length"]?.single()?.toInt() ?: 0
            check(size in 0..65_536)
            val raw = ByteArray(size)
            var offset = 0
            while (offset < size) {
                val count = input.read(raw, offset, size - offset)
                check(count > 0)
                offset += count
            }
            val body = if (size == 0) null else Json.parseToJsonElement(raw.toString(Charsets.UTF_8)).jsonObject
            val method = body?.get("method")?.jsonPrimitive?.content
            val auth = headers["authorization"].orEmpty().toList()
            synchronized(captured) {
                captured += Request(requestLine[0], method, auth, headers["x-synthetic-probe"]?.single())
                check(captured.size <= 24)
            }
            val (code, response) = when {
                auth != listOf(expectedAuthorization.get()) -> 401 to """{"error":"invalid_token","error_description":"Authentication required"}"""
                requestLine[0] == "GET" -> 405 to ""
                requestLine[0] == "DELETE" -> 204 to ""
                method == "notifications/initialized" -> 202 to ""
                method == "initialize" -> 200 to buildJsonObject {
                    put("jsonrpc", "2.0"); put("id", checkNotNull(body)["id"]!!)
                    put("result", buildJsonObject {
                        put("protocolVersion", body.getValue("params").jsonObject.getValue("protocolVersion"))
                        put("capabilities", buildJsonObject { put("tools", buildJsonObject {}) })
                        put("serverInfo", buildJsonObject { put("name", "synthetic"); put("version", "1.0") })
                    })
                }.toString()
                method == "tools/list" -> 200 to """{"jsonrpc":"2.0","id":${checkNotNull(body)["id"]},"result":{"tools":[]}}"""
                else -> error("Unexpected synthetic RPC")
            }
            val bytes = response.toByteArray(Charsets.UTF_8)
            val extra = if (method == "initialize" && code == 200) "Mcp-Session-Id: synthetic-session\r\n" else ""
            val output = socket.getOutputStream()
            output.write(("HTTP/1.1 $code Synthetic\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\n" +
                "Connection: close\r\n$extra\r\n").toByteArray(Charsets.US_ASCII))
            output.write(bytes)
            output.flush()
        }

        fun assertHealthy() { failure.get()?.let { throw AssertionError("Synthetic server failed", it) } }

        override fun close() {
            closed.set(true)
            listener.close()
            active.getAndSet(null)?.close()
            httpClient.close()
            okHttp.connectionPool.evictAll()
            okHttp.dispatcher.executorService.shutdownNow()
            worker.join(1_000)
            assertFalse("Synthetic server did not stop", worker.isAlive)
            assertHealthy()
        }
    }
}
