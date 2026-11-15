package me.rerere.ai.util

import java.io.IOException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test

class GatewayImageContinuationTest {
    private val endpoint = "https://continuation.invalid/v1/chat/completions".toHttpUrl()
    private val oldImage = "data:image/png;base64,AQIDBA=="
    private val newImage = "data:image/png;base64,BQYHCA=="
    private val reference = "st-image://" + "a".repeat(64)
    private fun imageMessage(url: String) = buildJsonObject {
        put("role", "user"); putJsonArray("content") { addJsonObject {
            put("type", "image_url"); putJsonObject("image_url") { put("url", url); put("detail", "high") }
        } }
    }
    private val completedTools = listOf(
        buildJsonObject { put("role", "assistant"); putJsonArray("tool_calls") { addJsonObject {
            put("id", "completed-tool"); put("type", "function"); putJsonObject("function") {
                put("name", "save_note"); put("arguments", "{}")
            }
        } } },
        buildJsonObject { put("role", "tool"); put("tool_call_id", "completed-tool"); put("content", "already saved") },
    )
    private fun body(new: Boolean = false, tools: Boolean = false) = buildJsonObject {
        put("model", "model"); put("messages", JsonArray(listOf(imageMessage(oldImage)) +
            (if (tools) completedTools else emptyList()) + (if (new) listOf(imageMessage(newImage)) else emptyList())))
    }
    private fun sent(r: Request) = json.parseToJsonElement(Buffer().also { r.body!!.writeTo(it) }.readUtf8()).jsonObject
    private fun response(r: Request, refs: Map<String, String>) = Response.Builder().request(r)
        .protocol(Protocol.HTTP_1_1).code(200).message("synthetic").body(buildJsonObject {
            put("protocol", "st-image-files/1"); put("supported", true)
            put("references", JsonObject(refs.mapValues { JsonPrimitive(it.value) }))
        }.toString().toResponseBody()).build()

    @Test fun toolContinuationKeepsConfirmedReferenceWithoutAnotherLookup() = runBlocking {
        var calls = 0
        val gateway = GatewayImageFiles { r ->
            check(++calls == 1) { "a confirmed image should not require another network lookup" }
            response(r, sent(r)["digests"]!!.jsonArray.associate { it.jsonPrimitive.content to reference })
        }
        val turn = GatewayImageTurn()
        val first = gateway.project(body(), endpoint, "Bearer synthetic", turn)
        repeat(4) {
            val next = gateway.project(body(tools = true), endpoint, "Bearer synthetic", turn)
            assertEquals(first["messages"]!!.jsonArray.first(), next["messages"]!!.jsonArray.first())
            assertEquals(completedTools, next["messages"]!!.jsonArray.drop(1))
        }
        assertEquals(1, calls)
    }

    @Test fun newImageLookupFailureKeepsOldReferenceNewOriginalAndCompletedToolResult() = runBlocking {
        var calls = 0
        val gateway = GatewayImageFiles { r ->
            if (++calls == 1) response(r, sent(r)["digests"]!!.jsonArray.associate { it.jsonPrimitive.content to reference })
            else throw IOException("synthetic dropped connection")
        }
        val turn = GatewayImageTurn()
        val first = gateway.project(body(), endpoint, "Bearer synthetic", turn)
        val continuation = body(new = true, tools = true)
        val next = gateway.project(continuation, endpoint, "Bearer synthetic", turn)
        assertEquals(first["messages"]!!.jsonArray.first(), next["messages"]!!.jsonArray.first())
        assertEquals(continuation["messages"]!!.jsonArray.drop(1), next["messages"]!!.jsonArray.drop(1))
        assertEquals(next, gateway.project(continuation, endpoint, "Bearer synthetic", turn))
        assertEquals(2, calls)
        // A different generation does retry the optional optimization; no failure is persisted.
        assertSame(continuation, gateway.project(continuation, endpoint, "Bearer synthetic", GatewayImageTurn()))
        assertEquals(3, calls)
    }

    @Test fun longTurnRefreshesExpiringHandlesAndScopeCannotChange() = runBlocking {
        var now = 0L; var calls = 0; var uploads = 0
        val gateway = GatewayImageFiles { r ->
            val payload = sent(r); calls++
            if (calls == 1) now = 60_000 // Time spent awaiting a slow lookup must count against validity.
            // The second lookup reports the old file near expiry, requiring a fresh upload.
            val refs = when {
                "sha256" in payload -> { uploads++; mapOf(payload["sha256"]!!.jsonPrimitive.content to reference) }
                calls == 2 -> emptyMap()
                else -> payload["digests"]!!.jsonArray.associate { it.jsonPrimitive.content to reference }
            }
            response(r, refs)
        }
        val turn = GatewayImageTurn { now }
        val first = gateway.project(body(), endpoint, "Bearer synthetic", turn)
        now = 239_999
        assertEquals(first, gateway.project(body(), endpoint, "Bearer synthetic", turn)); assertEquals(1, calls)
        now = 240_000
        assertEquals(first, gateway.project(body(), endpoint, "Bearer synthetic", turn))
        assertEquals(3, calls); assertEquals(1, uploads)
        try { gateway.project(body(), endpoint, "Bearer another", turn); fail("must not reuse another account's handles") }
        catch (e: IllegalStateException) { assertEquals("image_files_scope_changed", e.message) }
        assertEquals(3, calls)
    }

    @Test fun uploadOutageAfterConfirmedHistoryPreservesTheWholeContinuation() = runBlocking {
        for (status in listOf(429, 500, 502, 503, 504)) {
            var calls = 0
            val gateway = GatewayImageFiles { r ->
                val payload = sent(r); calls++
                when {
                    calls == 1 -> response(r, payload["digests"]!!.jsonArray.associate { it.jsonPrimitive.content to reference })
                    "digests" in payload -> { assertEquals(1, payload["digests"]!!.jsonArray.size); response(r, emptyMap()) }
                    else -> response(r, emptyMap()).newBuilder().code(status).build()
                }
            }
            val turn = GatewayImageTurn()
            val first = gateway.project(body(), endpoint, "Bearer synthetic", turn)
            val continuation = body(new = true, tools = true)
            val next = gateway.project(continuation, endpoint, "Bearer synthetic", turn)
            assertEquals(first["messages"]!!.jsonArray.first(), next["messages"]!!.jsonArray.first())
            assertEquals(continuation["messages"]!!.jsonArray.drop(1), next["messages"]!!.jsonArray.drop(1))
            assertEquals(next, gateway.project(continuation, endpoint, "Bearer synthetic", turn))
            assertEquals(3, calls)
        }
    }
}
