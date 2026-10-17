package me.rerere.search

import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class DeepSeekSearchServiceTest {
    private val service = DeepSeekSearchService
    private fun query(text: String = "DeepSeek site:example.com after:2026") = buildJsonObject { put("query", text) }
    private fun url(text: String) = buildJsonObject { put("url", text) }

    @Test fun registeredProviderSupportsSearchAndFetchWithoutChangingDefault() {
        val option = SearchServiceOptions.DeepSeekOptions()
        assertSame(service, SearchService.getService(option))
        assertEquals("DeepSeek 官方搜索", option.displayName)
        assertNotNull(service.parameters(option)); assertNotNull(service.scrapingParameters(option))
        assertTrue(SearchServiceOptions.DEFAULT is SearchServiceOptions.BingLocalOptions)
        assertEquals("", option.apiKey)
    }

    @Test fun providerRoundTripsAlongsideExistingProviders() {
        val options: List<SearchServiceOptions> = listOf(SearchServiceOptions.BochaOptions(),
            SearchServiceOptions.DeepSeekOptions(apiKey = "synthetic-key"), SearchServiceOptions.BingLocalOptions())
        val encoded = SearchService.json.encodeToString(options)
        val decoded = SearchService.json.decodeFromString<List<SearchServiceOptions>>(encoded)
        assertEquals(options.map { it.id }, decoded.map { it.id })
        assertEquals("synthetic-key", (decoded[1] as SearchServiceOptions.DeepSeekOptions).apiKey)
        assertTrue(decoded[0] is SearchServiceOptions.BochaOptions)
        assertTrue(decoded[2] is SearchServiceOptions.BingLocalOptions)
    }

    @Test fun searchOnlySendsWhitelistedFieldsAndPreservesQuerySyntax() {
        val body = service.searchBody(buildJsonObject {
            put("query", "DeepSeek site:example.com after:2026")
            put("localization", "private-ip")
            put("ori_question", "private full chat")
            put("api_key", "do-not-send")
        }, 10)
        assertEquals(setOf("query", "count", "need_content"), body.keys)
        assertEquals("DeepSeek site:example.com after:2026", body["query"]!!.jsonPrimitive.content)
        assertFalse(body["need_content"]!!.jsonPrimitive.boolean)
    }

    @Test fun countIsClampedToOfficialRange() {
        assertEquals(1, service.searchBody(query(), 0)["count"]!!.jsonPrimitive.int)
        assertEquals(50, service.searchBody(query(), 999)["count"]!!.jsonPrimitive.int)
    }

    @Test fun unicodeLimitUsesCharactersNotUtf16Units() {
        assertEquals("😀".repeat(128), service.searchBody(query("😀".repeat(128)), 10)["query"]!!.jsonPrimitive.content)
        assertThrows(IllegalArgumentException::class.java) { service.searchBody(query("😀".repeat(129)), 10) }
    }

    @Test fun invalidQueryIsRejectedWithoutSilentTruncation() {
        for (params in listOf(query("  "), query("x".repeat(129)), buildJsonObject { put("query", JsonNull) },
            buildJsonObject { put("query", 12) }, buildJsonObject {})) {
            assertThrows(IllegalArgumentException::class.java) { service.searchBody(params, 10) }
        }
    }

    @Test fun fetchValidatesSchemeCredentialsAndLengthBeforeSending() {
        for (value in listOf("file:///private", "javascript:alert(1)", "https://user:pass@example.com/",
            "https://user@example.com/", "", "https://example.com/" + "a".repeat(8192))) {
            assertThrows(IllegalArgumentException::class.java) { service.fetchBody(url(value)) }
        }
        assertEquals("https://example.com/article?q=1", service.fetchBody(url("https://example.com/article?q=1"))["url"]!!.jsonPrimitive.content)
    }

    @Test fun requestsGoToOfficialEndpointWithSeparateBearerAndJsonBody() {
        for (operation in listOf("search", "fetch")) {
            val request = service.request(operation, query(), "synthetic-key")
            assertEquals("https://api.deepseek.com/v1/tools/$operation", request.url.toString())
            assertEquals("Bearer synthetic-key", request.header("Authorization"))
            assertEquals("POST", request.method)
            val buffer = Buffer(); request.body!!.writeTo(buffer)
            assertFalse(buffer.readUtf8().contains("synthetic-key"))
        }
    }

    @Test fun keyValidationDoesNotEchoSecrets() {
        for (key in listOf("", "synthetic\nsecret", "含私密字符")) {
            val failure = assertThrows(IllegalArgumentException::class.java) { service.request("search", query(), key) }
            if (key.isNotEmpty()) assertFalse(failure.message.orEmpty().contains(key))
        }
    }

    @Test fun missingKeyAndInvalidQueryFailWithoutNetwork() = runBlocking {
        assertTrue(service.search(query(), SearchCommonOptions(), SearchServiceOptions.DeepSeekOptions()).isFailure)
        assertTrue(service.search(query(""), SearchCommonOptions(), SearchServiceOptions.DeepSeekOptions(apiKey="synthetic")).isFailure)
    }

    @Test fun searchPreservesOrderUrlsSummariesAndPublicationDates() {
        val result = service.parseSearch("""{"results":[
            {"title":"One","url":"https://example.com/a","summary":"For the model","snippet":"Short",
             "date":"2026-10-17T00:00:00Z","authority":3,"rerank_score":2.7},
            {"title":"Two","url":"https://example.com/b","summary":"","snippet":"Fallback","date":null}] }""")
        assertEquals(listOf("One", "Two"), result.items.map { it.title })
        assertEquals("For the model", result.items[0].text)
        assertEquals("Fallback", result.items[1].text)
        assertEquals("2026-10-17T00:00:00Z", result.items[0].publishedDate)
        assertNull(result.items[1].publishedDate)
        assertNull(result.retrievedAt); assertTrue(result.images.isEmpty())
    }

    @Test fun emptySearchIsValidButMalformedResponseIsNotSuccess() {
        assertTrue(service.parseSearch("""{"results":[]}""").items.isEmpty())
        for (raw in listOf("{}", """{"error":{"message":"secret-source"}}""", "not-json-secret-source")) {
            val error = assertThrows(IllegalStateException::class.java) { service.parseSearch(raw) }
            assertFalse(error.toString().contains("secret-source")); assertNull(error.cause)
        }
    }

    @Test fun fetchPreservesMarkdownAndNeverInventsADate() {
        val result = service.parseFetch("""{"title":"Source","content":"# Source\nBody"}""", "https://example.com/a")
        assertEquals("# Source\nBody", result.urls.single().content)
        assertEquals("Source", result.urls.single().metadata?.title)
        assertNull(result.urls.single().metadata?.publishedDate)
        assertEquals("https://example.com/a", result.urls.single().url)
        assertThrows(IllegalStateException::class.java) { service.parseFetch("""{"content":""}""", "https://example.com/a") }
    }

    @Test fun transportDisablesLoggingRedirectsAutomaticRetriesAndCookies() {
        val proxyAuth = okhttp3.Authenticator { _, _ -> null }
        val configured = OkHttpClient.Builder().addInterceptor { error("must not log") }
            .addNetworkInterceptor { error("must not log") }.proxyAuthenticator(proxyAuth).build()
        val client = service.client(configured)
        assertTrue(client.interceptors.isEmpty()); assertTrue(client.networkInterceptors.isEmpty())
        assertFalse(client.followRedirects); assertFalse(client.followSslRedirects)
        assertFalse(client.retryOnConnectionFailure); assertNull(client.cache)
        assertEquals(40000, client.callTimeoutMillis)
        assertSame(proxyAuth, client.proxyAuthenticator)
    }

    @Test fun completeHttpSearchAndFetchResponsesCanBeConsumed() = runBlocking {
        withServer { server ->
            server.respond("""{"results":[{"title":"Local","url":"https://example.com/","summary":"Read me"}]}""") { request ->
                val raw = service.execute(request, service.client(OkHttpClient()))
                assertEquals("Read me", service.parseSearch(raw).items.single().text)
            }
        }
        withServer { server ->
            server.respond("""{"title":"Page","content":"# Markdown"}""") { request ->
                assertEquals("# Markdown", service.parseFetch(service.execute(request, service.client(OkHttpClient())),
                    "https://example.com/").urls.single().content)
            }
        }
    }

    @Test fun httpErrorsNeverExposeResponseBodyAndAreNotRetried() = runBlocking {
        for (status in listOf(401, 402, 403, 404, 429, 500)) withServer { server ->
            server.respond("private-error-body", status) { request ->
                val error = runCatching { service.execute(request, service.client(OkHttpClient())) }.exceptionOrNull()!!
                assertTrue(error.message.orEmpty().contains("HTTP $status"))
                assertFalse(error.toString().contains("private-error-body"))
            }
        }
    }

    @Test fun redirectsStopAtOriginalServer() = runBlocking {
        withServer { server ->
            server.respond("", 302, "Location: http://127.0.0.1:1/must-not-contact\r\n") { request ->
                val failure = runCatching { service.execute(request, service.client(OkHttpClient())) }.exceptionOrNull()!!
                assertTrue(failure.message.orEmpty().contains("HTTP 302"))
            }
        }
    }

    @Test fun oversizedResponseIsRejectedBeforeAllocatingBody() = runBlocking {
        withServer { server ->
            server.respond("", extra = "", declaredLength = 5 * 1024 * 1024) { request ->
                val failure = runCatching { service.execute(request, service.client(OkHttpClient())) }.exceptionOrNull()!!
                assertTrue(failure.message.orEmpty().contains("内容过大"))
            }
        }
    }

    @Test fun cancellingWhileReadingBodyClosesSocketAndDoesNotBecomeSuccess() = runBlocking {
        withServer { server ->
            val bodyStarted = CompletableDeferred<Unit>()
            val worker = server.pool.submit<Boolean> {
                server.socket.accept().use { peer ->
                    peer.soTimeout = 4000; consumeRequest(peer)
                    peer.getOutputStream().apply { write("HTTP/1.1 200 OK\r\nContent-Length: 100000\r\n\r\nx".toByteArray()); flush() }
                    bodyStarted.complete(Unit)
                    peer.getInputStream().read() == -1
                }
            }
            val job = async(Dispatchers.IO) { service.execute(server.request, service.client(OkHttpClient())) }
            withTimeout(5000) { bodyStarted.await() }
            job.cancelAndJoin()
            assertTrue(job.isCancelled)
            assertTrue(worker.get(5, TimeUnit.SECONDS))
        }
    }

    @Test fun stalledBodyTimesOutAndClosesConnectionWithoutRetry() = runBlocking {
        withServer { server ->
            val worker = server.pool.submit<Boolean> {
                server.socket.accept().use { peer ->
                    peer.soTimeout = 4000; consumeRequest(peer)
                    peer.getOutputStream().apply {
                        write("HTTP/1.1 200 OK\r\nContent-Length: 100000\r\n\r\nx".toByteArray()); flush()
                    }
                    peer.getInputStream().read() == -1
                }
            }
            val client = service.client(OkHttpClient()).newBuilder()
                .callTimeout(500, TimeUnit.MILLISECONDS).build()
            val error = runCatching { withTimeout(5000) { service.execute(server.request, client) } }.exceptionOrNull()!!
            assertTrue(error is java.io.IOException)
            assertTrue(error.message.orEmpty().contains("超时"))
            assertTrue(worker.get(5, TimeUnit.SECONDS))
        }
    }

    private suspend fun withServer(block: suspend (LocalServer) -> Unit) {
        LocalServer().use { block(it) }
    }
    private class LocalServer : AutoCloseable {
        val socket = ServerSocket().apply { bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0)); soTimeout = 5000 }
        val pool = Executors.newSingleThreadExecutor()
        val request: Request get() = Request.Builder().url("http://127.0.0.1:${socket.localPort}/synthetic").build()
        suspend fun respond(body: String, status: Int = 200, extra: String = "", declaredLength: Int = body.toByteArray().size,
            check: suspend (Request) -> Unit) {
            val task = pool.submit {
                socket.accept().use { peer ->
                    peer.soTimeout = 5000; consumeRequest(peer)
                    peer.getOutputStream().apply {
                        write("HTTP/1.1 $status Test\r\nContent-Length: $declaredLength\r\nConnection: close\r\n$extra\r\n".toByteArray())
                        write(body.toByteArray()); flush()
                    }
                }
            }
            check(request); task.get(5, TimeUnit.SECONDS)
        }
        override fun close() { socket.close(); pool.shutdownNow() }
    }
    companion object {
        private fun consumeRequest(peer: Socket) {
            val input = peer.getInputStream(); val header = StringBuilder()
            while (!header.endsWith("\r\n\r\n")) {
                val c = input.read(); check(c >= 0); header.append(c.toChar()); check(header.length < 65536)
            }
        }
    }
}
