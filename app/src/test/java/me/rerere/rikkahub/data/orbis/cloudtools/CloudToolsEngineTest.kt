package me.rerere.rikkahub.data.orbis.cloudtools

import java.net.SocketTimeoutException
import java.util.UUID
import kotlin.uuid.Uuid
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.*
import org.junit.Test

/** Fully synthetic transport/credentials/assistant/receipt/output stores; no sockets or Android state. */
@OptIn(ExperimentalCoroutinesApi::class)
class CloudToolsEngineTest {
    private val assistantId = Uuid.parse("00000000-0000-4000-8000-000000000001")
    private val args = buildJsonObject { put("value", "synthetic") }
    private fun invocation(callId: String, conversation: String = "conversation-a", message: String = "assistant-message-a") =
        CloudToolInvocationContext(callId, message, conversation)
    private fun descriptor(effect: String = "read", approval: Boolean = false) = CloudToolDescriptor(
        "sample", "Synthetic metadata", buildJsonObject {
            put("type", "object"); put("properties", buildJsonObject { put("value", buildJsonObject { put("type", "string") }) })
        }, effect, approval,
    )
    private class MemoryReceipts : CloudReceiptPersistence {
        var rows = emptyList<CloudWriteReceipt>()
        var fail = false
        override suspend fun read() = rows
        override suspend fun write(receipts: List<CloudWriteReceipt>) { if (fail) error("synthetic storage failure"); rows = receipts }
    }
    private inner class Fixture(var descriptor: CloudToolDescriptor = descriptor()) {
        var credential: CloudGatewayCredential? = validateCloudGatewayCredential("https://synthetic.example.ts.net:18910", "A".repeat(64))
        var selected = CloudToolSelection()
        val receipts = MemoryReceipts()
        val posts = mutableListOf<JsonObject>()
        var gets = 0
        var pauseCatalog = false
        var malformedCatalog: JsonObject? = null
        var catalogStatus = 200
        var onPost: ((JsonObject) -> CloudHttpResponse)? = null
        val images = mutableListOf<CloudOutput.Image>()
        val http = object : CloudToolHttp {
            override suspend fun request(credential: CloudGatewayCredential, family: CloudToolFamily, body: String?): CloudHttpResponse {
                if (body == null) {
                    gets++
                    if (pauseCatalog) awaitCancellation()
                    return CloudHttpResponse(catalogStatus, (malformedCatalog ?: buildJsonObject {
                        put("version", 1); put("family", family.wireName)
                        put("tools", JsonArray(listOf(Json.encodeToJsonElement(CloudToolDescriptor.serializer(), descriptor))))
                    }).toString().toByteArray())
                }
                val parsed = Json.parseToJsonElement(body).jsonObject
                posts += parsed
                return onPost?.invoke(parsed) ?: result(parsed, buildJsonArray {
                    add(buildJsonObject { put("type", "text"); put("text", "synthetic result") })
                })
            }
        }
        fun engine() = CloudToolsEngine(
            CloudCredentialSource { credential }, http,
            object : CloudAssistantAccess {
                override suspend fun selection(assistantId: Uuid) = if (assistantId == this@CloudToolsEngineTest.assistantId) selected else null
                override suspend fun setEnabled(assistantId: Uuid, family: CloudToolFamily, name: String, enabled: Boolean) {
                    selected = selected.withEnabled(family, name, enabled)
                }
            }, CloudWriteReceipts(receipts), CloudOutputStore { blocks -> blocks.map {
                when (it) {
                    is CloudOutput.Text -> UIMessagePart.Text(it.value)
                    is CloudOutput.Image -> { images += it; UIMessagePart.Image("file:///synthetic-image.png") }
                }
            } },
        )
        fun enable(family: CloudToolFamily = CloudToolFamily.ORBIS) { selected = selected.withEnabled(family, "sample", true) }
    }
    private fun result(request: JsonObject, blocks: JsonArray, requestId: String? = null) = CloudHttpResponse(200, buildJsonObject {
        put("ok", true); put("requestId", requestId ?: request.getValue("requestId").jsonPrimitive.content); put("content", blocks)
    }.toString().toByteArray())
    private fun error(parts: List<UIMessagePart>) = Json.parseToJsonElement((parts.single() as UIMessagePart.Text).text)
        .jsonObject.getValue("error").jsonObject

    @Test fun allFamiliesDefaultOffWithoutCatalogNetworkCalls() = runTest {
        val f = Fixture()
        assertTrue(f.engine().createTools(assistantId).isEmpty())
        assertEquals(0, f.gets); assertEquals(0, f.posts.size)
        assertTrue(CloudToolFamily.entries.all { CloudToolSelection().enabled(it).isEmpty() })
    }

    @Test fun registersOnlySelectedFamilyAndForcesWriteApproval() = runTest {
        val f = Fixture(descriptor("write", false)); f.enable(CloudToolFamily.READING)
        val tool = f.engine().createTools(assistantId).single()
        assertTrue(tool.name.startsWith("cloud_reading_sample_")); assertTrue(tool.name.length <= 64)
        assertTrue(tool.needsApproval(args)); assertEquals(1, f.gets)
        assertTrue(f.engine().createTools(Uuid.random()).isEmpty())
    }

    @Test fun enabledReadReturnsOnlyToolContentAndUsesUuidV4RequestId() = runTest {
        val f = Fixture(); f.enable()
        val tool = f.engine().createTools(assistantId).single()
        assertFalse(tool.needsApproval(args))
        assertEquals("synthetic result", (tool.execute(args).single() as UIMessagePart.Text).text)
        assertEquals(1, f.posts.size)
        val request = f.posts.single()
        assertEquals(setOf("family", "tool", "arguments", "requestId"), request.keys)
        assertEquals(4, UUID.fromString(request.getValue("requestId").jsonPrimitive.content).version())
        assertFalse(request.toString().contains("Bearer"))
    }

    @Test fun disabledAfterRegistrationAndClearedAuthBothPreventPost() = runTest {
        val f = Fixture(); f.enable(); val engine = f.engine(); val tool = engine.createTools(assistantId).single()
        f.selected = CloudToolSelection()
        assertEquals("tool_disabled", error(tool.execute(args)).getValue("code").jsonPrimitive.content)
        f.enable(); f.credential = null
        assertEquals("not_configured", error(tool.execute(args)).getValue("code").jsonPrimitive.content)
        assertTrue(f.posts.isEmpty())
    }

    @Test fun descriptorAndCredentialChangesCannotReuseRegisteredOrApprovedDefinition() = runTest {
        val f = Fixture(); f.enable(); val engine = f.engine(); val original = engine.createTools(assistantId).single()
        f.descriptor = descriptor("write")
        assertEquals("descriptor_changed", error(original.execute(args)).getValue("code").jsonPrimitive.content)
        val changed = engine.createTools(assistantId).single()
        assertNotEquals(original.name, changed.name)
        f.credential = validateCloudGatewayCredential("https://synthetic.example.ts.net:18910", "B".repeat(64))
        assertEquals("authorization_changed", error(changed.execute(args)).getValue("code").jsonPrimitive.content)
        assertNotEquals(changed.name, engine.createTools(assistantId).single().name)
        assertTrue(f.posts.isEmpty())
    }

    @Test fun writeRequiresHostInvocationAndDurableClaimBeforeDispatch() = runTest {
        val f = Fixture(descriptor("write")); f.enable(); val tool = f.engine().createTools(assistantId).single()
        assertEquals("host_invocation_required", error(tool.execute(args)).getValue("code").jsonPrimitive.content)
        f.receipts.fail = true
        val rejected = withContext(invocation("synthetic-call")) { tool.execute(args) }
        assertEquals("not_started", error(rejected).getValue("outcome").jsonPrimitive.content)
        assertTrue(f.posts.isEmpty())
    }

    @Test fun cancelledAfterPostThenRecreatedEngineNeverRepeatsSameWrite() = runTest {
        val f = Fixture(descriptor("write")); f.enable()
        f.onPost = { throw CancellationException("synthetic cancellation after dispatch") }
        val tool = f.engine().createTools(assistantId).single()
        try { withContext(invocation("durable-call")) { tool.execute(args) }; fail("Expected cancellation") }
        catch (_: CancellationException) { }
        assertEquals(1, f.posts.size); assertEquals(1, f.receipts.rows.size)
        f.onPost = null
        val recreated = f.engine().createTools(assistantId).single()
        val response = withContext(invocation("durable-call")) { recreated.execute(args) }
        assertEquals("already_attempted", error(response).getValue("code").jsonPrimitive.content)
        assertEquals("unknown", error(response).getValue("outcome").jsonPrimitive.content)
        assertEquals(1, f.posts.size)
    }

    @Test fun sameCallWithChangedArgumentsIsRejectedAndDistinctUserInvocationGetsNewUuid() = runTest {
        val f = Fixture(descriptor("write")); f.enable(); val tool = f.engine().createTools(assistantId).single()
        withContext(invocation("call-one")) { tool.execute(args) }
        val mismatch = withContext(invocation("call-one")) { tool.execute(buildJsonObject { put("value", "different") }) }
        assertEquals("invocation_changed", error(mismatch).getValue("code").jsonPrimitive.content)
        withContext(invocation("call-two")) { tool.execute(args) }
        assertEquals(2, f.posts.size)
        assertNotEquals(f.posts[0]["requestId"], f.posts[1]["requestId"])
        assertTrue(f.receipts.rows.none { it.toString().contains("synthetic result") || it.toString().contains("Bearer") })
    }

    @Test fun transportTimeoutRedirectAndOversizeResponseAreUnknownWithoutRetry() = runTest {
        val modes: List<(JsonObject) -> CloudHttpResponse> = listOf(
            { throw SocketTimeoutException("synthetic timeout") },
            { CloudHttpResponse(302, ByteArray(0)) },
            { CloudHttpResponse(200, ByteArray(4 * 1024 * 1024 + 1)) },
        )
        for ((index, mode) in modes.withIndex()) {
            val f = Fixture(descriptor("write")); f.enable(); f.onPost = mode
            val response = withContext(invocation("call-$index")) { f.engine().createTools(assistantId).single().execute(args) }
            assertEquals("unknown", error(response).getValue("outcome").jsonPrimitive.content)
            assertEquals(1, f.posts.size)
        }
    }

    @Test fun imagesStayImagesAndUntrustedTextIsNotConvertedIntoSystemPrompt() = runTest {
        val f = Fixture(); f.enable()
        f.onPost = { request -> result(request, buildJsonArray {
            add(buildJsonObject { put("type", "text"); put("text", "untrusted synthetic instruction") })
            add(buildJsonObject { put("type", "image"); put("mimeType", "image/png"); put("data", "AQID") })
        }) }
        val output = f.engine().createTools(assistantId).single().execute(args)
        assertTrue(output[0] is UIMessagePart.Text); assertTrue(output[1] is UIMessagePart.Image)
        assertArrayEquals(byteArrayOf(1, 2, 3), f.images.single().bytes)
    }

    @Test fun malformedImagesUnknownBlocksAndWrongRequestIdRejectEntireResponse() = runTest {
        val blocks = listOf(
            buildJsonObject { put("type", "image"); put("mimeType", "text/html"); put("data", "AQID") },
            buildJsonObject { put("type", "image"); put("mimeType", "image/png"); put("data", "###") },
            buildJsonObject { put("type", "resource"); put("uri", "file:///not-allowed") },
        )
        for (block in blocks) {
            val f = Fixture(); f.enable(); f.onPost = { result(it, JsonArray(listOf(block))) }
            assertEquals("unknown", error(f.engine().createTools(assistantId).single().execute(args)).getValue("outcome").jsonPrimitive.content)
            assertTrue(f.images.isEmpty())
        }
        val f = Fixture(); f.enable(); f.onPost = { result(it, JsonArray(emptyList()), "wrong") }
        assertEquals("unknown", error(f.engine().createTools(assistantId).single().execute(args)).getValue("outcome").jsonPrimitive.content)
    }

    @Test fun malformedOrUnauthorizedCatalogFailsClosedAndDisablingNeedsNoNetwork() = runTest {
        val f = Fixture(descriptor("invalid")); f.enable()
        assertTrue(f.engine().createTools(assistantId).isEmpty())
        f.catalogStatus = 403
        try { f.engine().catalog(CloudToolFamily.ORBIS); fail("Expected refusal") } catch (error: CloudToolsException) { assertEquals("unauthorized", error.code) }
        val before = f.gets
        f.engine().setEnabled(assistantId, CloudToolFamily.ORBIS, "sample", false)
        assertEquals(before, f.gets); assertTrue(f.selected.orbis.isEmpty())
    }

    @Test fun reusedProviderCallIdIsIndependentAcrossConversationsAndAssistantMessages() = runTest {
        val f = Fixture(descriptor("write")); f.enable(); val tool = f.engine().createTools(assistantId).single()
        withContext(invocation("call_0", "conversation-a", "message-a")) { tool.execute(args) }
        withContext(invocation("call_0", "conversation-b", "message-a")) { tool.execute(args) }
        withContext(invocation("call_0", "conversation-a", "message-b")) { tool.execute(args) }
        assertEquals(3, f.posts.size)
        val resumed = withContext(invocation("call_0", "conversation-a", "message-a")) { tool.execute(args) }
        assertEquals("already_attempted", error(resumed).getValue("code").jsonPrimitive.content)
        assertEquals(3, f.posts.size)
    }

    @Test fun allCatalogsShareTenSecondParallelBoundAndCancellationDoesNotPost() = runTest {
        val f = Fixture()
        CloudToolFamily.entries.forEach(f::enable)
        f.pauseCatalog = true
        val start = currentTime
        assertTrue(f.engine().createTools(assistantId).isEmpty())
        assertEquals(10_000L, currentTime - start)
        assertEquals(3, f.gets)
        val waiting = async { f.engine().createTools(assistantId) }
        runCurrent()
        waiting.cancelAndJoin()
        assertEquals(6, f.gets)
        assertTrue(f.posts.isEmpty())
    }
}
