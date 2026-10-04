package me.rerere.rikkahub.data.orbis.integration

import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.Proxy
import java.net.ServerSocket
import java.nio.charset.StandardCharsets
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.GZIPOutputStream
import kotlin.concurrent.thread
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import me.rerere.rikkahub.data.orbis.OrbisMemoryAtlas as Atlas
import okhttp3.Authenticator
import okhttp3.CookieJar
import org.junit.Assert.*
import org.junit.Test

/** All metadata and credentials are synthetic. HTTP fixtures bind only to literal loopback. */
class OrbisAtlasClientTest {
    private val stamp = "2026-09-26T10:11:12Z"
    private val secret = "synthetic-atlas-token-" + "a".repeat(48)
    private fun id(value: Int) = value.toString(16).padStart(64, '0')
    private fun star(value: Int, type: String = "学习", time: String = stamp) =
        """{"id":"${id(value)}","type":"$type","storedAt":"$time"}"""
    private fun edge(a: Int, b: Int) = """{"a":"${id(a)}","b":"${id(b)}"}"""
    private fun document(stars: String = star(1), edges: String = "", truncated: Boolean = false) =
        """{"schema":"orbis.st.atlas/1","generatedAt":"$stamp","truncated":$truncated,"stars":[$stars],"edges":[$edges]}"""
    private fun parse(value: String) = parseOrbisAtlas(value.toByteArray(Charsets.UTF_8))
    private fun invalid(value: String) = invalid(value.toByteArray(Charsets.UTF_8))
    private fun invalid(value: ByteArray) {
        try {
            parseOrbisAtlas(value)
            fail("Expected fail-closed metadata rejection")
        } catch (error: OrbisAtlasException) {
            assertEquals("invalid_metadata", error.reason)
            assertNull(error.cause)
            assertFalse(error.toString().contains(secret))
        }
    }
    private fun replaceRoot(key: String, value: kotlinx.serialization.json.JsonElement): String {
        val root = Json.parseToJsonElement(document()).jsonObject
        return JsonObject(root + (key to value)).toString()
    }

    @Test fun realSnapshotIsExplicitlyDifferentFromDemoAndKeepsOnlyMetadata() {
        val value = parse(document(star(1), truncated = true))
        assertFalse(value.isDemo)
        assertEquals("st_metadata_endpoint", value.source)
        assertEquals(stamp, value.generatedAt)
        assertTrue(value.truncated)
        assertEquals(listOf(Atlas.Star(id(1), "学习", stamp)), value.stars)
        assertTrue(value.edges.isEmpty())
        assertFalse(value.toString().contains("demo_not_real_memory"))
    }

    @Test fun emptyRealAtlasDoesNotPopulateDemoStars() {
        val value = parse(document(stars = ""))
        assertTrue(value.stars.isEmpty())
        assertTrue(value.edges.isEmpty())
        assertFalse(value.isDemo)
        assertFalse(value.truncated)
    }

    @Test fun acceptsExplicitSupportedTypesAndCanonicalizesInstants() {
        val values = Atlas.SUPPORTED_TYPES.mapIndexed { index, type -> star(index, type, "2026-09-26T11:11:12+01:00") }
        val parsed = parse(document(values.joinToString(",")))
        assertEquals(Atlas.SUPPORTED_TYPES, parsed.stars.map { it.type })
        assertTrue(parsed.stars.all { it.storedAt == stamp })
    }

    @Test fun canonicalizesUndirectedEdgesAndDeduplicatesWithoutChangingStarOrder() {
        val value = parse(document("${star(9)},${star(2)},${star(7)}", "${edge(2, 9)},${edge(9, 2)},${edge(7, 2)}"))
        assertEquals(listOf(id(9), id(2), id(7)), value.stars.map { it.id })
        assertEquals(listOf(Atlas.Edge(0, 1), Atlas.Edge(1, 2)), value.edges)
    }

    @Test fun rejectsValidInstantsOutsideAdapterAndUiDateRange() {
        listOf(java.time.Instant.MAX.toString(), java.time.Instant.MIN.toString(),
            "+10000-01-01T00:00:00Z", "0000-12-31T23:59:59Z").forEach { extreme ->
            invalid(document(star(1, time = extreme)))
            invalid(replaceRoot("generatedAt", JsonPrimitive(extreme)))
        }
    }

    @Test fun acceptsAdapterDateBoundaryYears() {
        listOf("0001-01-01T00:00:00Z", "9999-12-31T23:59:59.999999999Z").forEach { boundary ->
            assertEquals(boundary, parse(document(star(1, time = boundary))).stars.single().storedAt)
        }
    }

    @Test fun rejectsWrongSchemaAndWrongSchemaJsonType() {
        listOf(JsonPrimitive("orbis.st.atlas/2"), JsonPrimitive("orbis.st.atlas/1 "), JsonPrimitive(1))
            .forEach { invalid(replaceRoot("schema", it)) }
    }

    @Test fun requiresEveryRootFieldAndRejectsRootExtrasIncludingPrivateText() {
        val root = Json.parseToJsonElement(document()).jsonObject
        root.keys.forEach { key -> invalid(JsonObject(root - key).toString()) }
        listOf("body", "summary", "content", "title", "token", "metadata").forEach { key ->
            invalid(JsonObject(root + (key to JsonPrimitive(secret))).toString())
        }
    }

    @Test fun rejectsNonObjectRootsAndMalformedJson() {
        listOf("", "null", "[]", "true", "1", "\"text\"", "{", "{} trailing", "{/*comment*/}")
            .forEach { invalid(it) }
    }

    @Test fun truncatedMustBeAJsonBoolean() {
        listOf(JsonPrimitive("true"), JsonPrimitive(0), JsonPrimitive("false"), JsonArray(emptyList()))
            .forEach { invalid(replaceRoot("truncated", it)) }
    }

    @Test fun starsAndEdgesMustBeArrays() {
        listOf("stars", "edges").forEach { key ->
            listOf(JsonPrimitive("[]"), JsonPrimitive(0), JsonObject(emptyMap()))
                .forEach { invalid(replaceRoot(key, it)) }
        }
    }

    @Test fun rejectsMissingAndExtraStarFieldsInsteadOfStrippingPrivateContent() {
        val base = Json.parseToJsonElement(star(1)).jsonObject
        base.keys.forEach { key -> invalid(document(JsonObject(base - key).toString())) }
        listOf("body", "summary", "title", "content", "tags", "private_id").forEach { key ->
            invalid(document(JsonObject(base + (key to JsonPrimitive(secret))).toString()))
        }
    }

    @Test fun starFieldsRequireStringsAndRejectNullOrNestedValues() {
        val base = Json.parseToJsonElement(star(1)).jsonObject
        base.keys.forEach { key ->
            listOf(JsonPrimitive(1), JsonPrimitive(false), JsonObject(emptyMap()), JsonArray(emptyList()),
                kotlinx.serialization.json.JsonNull).forEach { wrong ->
                invalid(document(JsonObject(base + (key to wrong)).toString()))
            }
        }
        invalid(document("null"))
    }

    @Test fun idRequiresExactly64LowercaseHexCharactersWithoutOriginalBucketIds() {
        listOf("", "4d27396aaa33", "a".repeat(63), "a".repeat(65), "A".repeat(64), "g".repeat(64),
            "x" + "a".repeat(63), " " + "a".repeat(63), "a".repeat(63) + "\\n").forEach { value ->
            invalid(document(star(1).replace(id(1), value)))
        }
    }

    @Test fun rejectsDuplicateStarIdsEvenWhenTheirMetadataDiffers() {
        invalid(document("${star(1)},${star(1, "情感")}"))
    }

    @Test fun rejectsUnknownTypesWithoutUsingOtherAsFallback() {
        listOf("", "学习 ", "工作推测", "其他<script>", "other").forEach { invalid(document(star(1, it))) }
    }

    @Test fun requiresParseableBoundedTimestampsInBothLocations() {
        listOf("", "yesterday", "2026-09-26", "2026-09-26T10:11:12", "2026-13-26T10:11:12Z", "x".repeat(81))
            .forEach { value ->
                invalid(document(star(1, time = value)))
                invalid(replaceRoot("generatedAt", JsonPrimitive(value)))
            }
        invalid(replaceRoot("generatedAt", JsonPrimitive(123)))
    }

    @Test fun requiresKnownDistinctEdgeEndpoints() {
        invalid(document(star(1), edge(1, 99)))
        invalid(document(star(1), edge(99, 1)))
        invalid(document(star(1), edge(1, 1)))
        invalid(document(stars = "", edges = edge(1, 2)))
    }

    @Test fun edgesRejectPrivateLabelsMissingFieldsAndNonStringEndpoints() {
        val base = Json.parseToJsonElement(edge(1, 2)).jsonObject
        val stars = "${star(1)},${star(2)}"
        base.keys.forEach { key -> invalid(document(stars, JsonObject(base - key).toString())) }
        invalid(document(stars, JsonObject(base + ("label" to JsonPrimitive(secret))).toString()))
        listOf("a", "b").forEach { key ->
            invalid(document(stars, JsonObject(base + (key to JsonPrimitive(1))).toString()))
        }
        invalid(document(stars, "null"))
    }

    @Test fun accepts2000StarsButRejects2001RatherThanSilentlyTruncating() {
        assertEquals(2000, parse(document((0 until 2000).joinToString(",") { star(it) })).stars.size)
        invalid(document((0 until 2001).joinToString(",") { star(it) }))
    }

    @Test fun edgeLimitCountsRawEntriesBeforeDeduplication() {
        val stars = "${star(1)},${star(2)}"
        assertEquals(1, parse(document(stars, List(5000) { edge(1, 2) }.joinToString(","))).edges.size)
        invalid(document(stars, List(5001) { edge(1, 2) }.joinToString(",")))
    }

    @Test fun acceptsExactByteBoundaryAndRejectsOneExtraByte() {
        val bytes = document().toByteArray(Charsets.UTF_8)
        val exact = bytes + ByteArray(ATLAS_RESPONSE_LIMIT - bytes.size) { ' '.code.toByte() }
        assertEquals(1, parseOrbisAtlas(exact).stars.size)
        invalid(exact + ' '.code.toByte())
    }

    @Test fun rejectsInvalidUtf8InsteadOfReplacingIt() {
        invalid(byteArrayOf(0xC3.toByte(), 0x28))
        val valid = document().toByteArray(Charsets.UTF_8)
        invalid(valid + byteArrayOf(0xFF.toByte()))
    }

    @Test fun rejectsDuplicateJsonKeysAtRootStarAndEdgeLevels() {
        invalid(document().replace("\"schema\":", "\"schema\":\"orbis.st.atlas/1\",\"schema\":"))
        invalid(document(star(1).replace("\"id\":", "\"id\":\"${id(2)}\",\"id\":")))
        invalid(document("${star(1)},${star(2)}", edge(1, 2).replace("\"a\":", "\"a\":\"${id(2)}\",\"a\":")))
    }

    @Test fun escapedDuplicateKeysCannotBypassUniqueness() {
        invalid(document().replace("\"schema\":", "\"sch\\u0065ma\":\"orbis.st.atlas/1\",\"schema\":"))
    }

    @Test fun deeplyNestedUnknownPayloadFailsWithoutStackOverflow() {
        invalid("{\"body\":" + "[".repeat(2048) + "0" + "]".repeat(2048) + "}")
    }

    @Test fun jsonDelimitersInsideQuotedTextNeverConfuseStructuralGuard() {
        // The braces/brackets are invalid metadata values, not structure; rejection remains sanitized.
        invalid(replaceRoot("generatedAt", JsonPrimitive("[".repeat(40) + "\\\"{}" + "]".repeat(40))))
        assertEquals(1, parse(document().replace("学习", "\\u5b66\\u4e60")).stars.size)
    }

    @Test fun httpClientDoesNotShareAuthenticationLoggingProxyCookiesCacheOrRetries() {
        val client = OrbisAtlasClient().client
        assertFalse(client.followRedirects)
        assertFalse(client.followSslRedirects)
        assertFalse(client.retryOnConnectionFailure)
        assertEquals(Proxy.NO_PROXY, client.proxy)
        assertSame(Authenticator.NONE, client.authenticator)
        assertSame(Authenticator.NONE, client.proxyAuthenticator)
        assertSame(CookieJar.NO_COOKIES, client.cookieJar)
        assertNull(client.cache)
        assertTrue(client.interceptors.isEmpty())
        assertTrue(client.networkInterceptors.isEmpty())
        assertTrue(client.connectTimeoutMillis in 1..10_000)
        assertTrue(client.readTimeoutMillis in 1..20_000)
        assertTrue(client.callTimeoutMillis in 1..30_000)
    }

    @Test fun loopbackRequestUsesOnlyGetMetadataRouteAndBearerHeader() = runBlocking {
        Fixture(document().toByteArray()).use { fixture ->
            val result = withTimeout(5_000) { OrbisAtlasClient().load(fixture.credential(secret)) }
            assertEquals(1, result.stars.size)
            val request = fixture.request.get() ?: error("Loopback request was not captured")
            assertEquals("GET /v1/atlas HTTP/1.1", request.first())
            assertTrue(request.any { it.equals("Authorization: Bearer $secret", ignoreCase = true) })
            assertTrue(request.any { it.equals("Accept: application/json", ignoreCase = true) })
            assertFalse(request.first().contains(secret))
            assertFalse(request.any { it.startsWith("Cookie:", ignoreCase = true) })
        }
    }

    @Test fun loopbackHttpStatusesBecomeFixedReasonsWithoutReadingErrorText() = runBlocking {
        listOf(401 to "unauthorized", 403 to "unauthorized", 404 to "endpoint_missing", 500 to "unavailable")
            .forEach { (status, expected) ->
                Fixture(secret.toByteArray(), status = status).use { fixture ->
                    expectLoadFailure(fixture, expected)
                }
            }
    }

    @Test fun loopbackRedirectIsNotFollowedAndCannotForwardToken() = runBlocking {
        Fixture(ByteArray(0), status = 302, extraHeaders = listOf("Location: http://127.0.0.1:1/forbidden"))
            .use { expectLoadFailure(it, "unavailable") }
    }

    @Test fun loopbackRejectsMissingOrNonJsonContentType() = runBlocking {
        listOf<String?>(null, "text/html", "application/octet-stream").forEach { type ->
            Fixture(document().toByteArray(), contentType = type).use { expectLoadFailure(it, "invalid_metadata") }
        }
    }

    @Test fun loopbackDeclaredOversizeAndOversizeUnknownLengthFailClosed() = runBlocking {
        Fixture(ByteArray(0), declaredLength = ATLAS_RESPONSE_LIMIT.toLong() + 1)
            .use { expectLoadFailure(it, "invalid_metadata") }
        Fixture(ByteArray(ATLAS_RESPONSE_LIMIT + 1) { ' '.code.toByte() }, declaredLength = null)
            .use { expectLoadFailure(it, "invalid_metadata") }
    }

    @Test fun loopbackGzipIsBoundedAfterDecompression() = runBlocking {
        val compressed = ByteArrayOutputStream().also { output ->
            GZIPOutputStream(output).use { it.write(ByteArray(ATLAS_RESPONSE_LIMIT + 1) { ' '.code.toByte() }) }
        }.toByteArray()
        Fixture(compressed, extraHeaders = listOf("Content-Encoding: gzip"))
            .use { expectLoadFailure(it, "invalid_metadata") }
    }

    @Test fun loopbackCancellationDoesNotPublishALateSuccessfulSnapshot() = runBlocking {
        val release = CountDownLatch(1)
        Fixture(document().toByteArray(), release = release).use { fixture ->
            val completed = CompletableDeferred<Atlas.Snapshot>()
            val task = async { completed.complete(OrbisAtlasClient().load(fixture.credential(secret))) }
            withTimeout(5_000) { fixture.accepted.await() }
            task.cancelAndJoin()
            release.countDown()
            assertFalse(completed.isCompleted)
            assertTrue(task.isCancelled)
        }
    }

    private suspend fun expectLoadFailure(fixture: Fixture, reason: String) {
        try {
            withTimeout(5_000) { OrbisAtlasClient().load(fixture.credential(secret)) }
            fail("Expected isolated HTTP rejection")
        } catch (error: OrbisAtlasException) {
            assertEquals(reason, error.reason)
            assertNull(error.cause)
            assertFalse(error.toString().contains(secret))
        }
    }

    private class Fixture(
        body: ByteArray,
        status: Int = 200,
        contentType: String? = "application/json; charset=utf-8",
        declaredLength: Long? = body.size.toLong(),
        extraHeaders: List<String> = emptyList(),
        private val release: CountDownLatch? = null,
    ) : AutoCloseable {
        private val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        private val peer = AtomicReference<java.net.Socket?>()
        val accepted = CompletableDeferred<Unit>()
        val request = AtomicReference<List<String>?>()
        private val worker = thread(name = "orbis-atlas-loopback-test", isDaemon = true) {
            try {
                server.accept().use { socket ->
                    peer.set(socket)
                    socket.soTimeout = 5_000
                    val reader = socket.getInputStream().bufferedReader(StandardCharsets.US_ASCII)
                    val headers = buildList {
                        while (true) {
                            val line = reader.readLine() ?: break
                            if (line.isEmpty()) break
                            add(line)
                        }
                    }
                    request.set(headers)
                    accepted.complete(Unit)
                    release?.await(5, TimeUnit.SECONDS)
                    val response = buildString {
                        append("HTTP/1.1 $status Fixture\r\n")
                        contentType?.let { append("Content-Type: $it\r\n") }
                        declaredLength?.let { append("Content-Length: $it\r\n") }
                        append("Connection: close\r\n")
                        extraHeaders.forEach { append("$it\r\n") }
                        append("\r\n")
                    }
                    socket.getOutputStream().apply {
                        write(response.toByteArray(StandardCharsets.US_ASCII))
                        write(body)
                        flush()
                    }
                }
            } catch (_: Exception) {
                // Closing/cancelling an intentionally truncated response is expected in these fixtures.
            } finally { peer.set(null) }
        }

        fun credential(token: String) = OrbisConnectionCredential("http://127.0.0.1:${server.localPort}", token, 1)

        override fun close() {
            release?.countDown()
            peer.get()?.runCatching { close() }
            server.close()
            worker.join(1_000)
        }
    }
}
