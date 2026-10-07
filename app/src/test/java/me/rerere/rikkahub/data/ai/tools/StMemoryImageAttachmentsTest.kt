package me.rerere.rikkahub.data.ai.tools

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.toMessageNode
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

class StMemoryImageAttachmentsTest {
    private val user = UIMessage.user("Keep this synthetic image").copy(parts = listOf(
        UIMessagePart.Text("Keep this synthetic image"), UIMessagePart.Image("file:///owned/upload/synthetic.png")))
    private val conversation = Conversation(assistantId = Uuid.random(), messageNodes = listOf(user.toMessageNode()))
    private val scope = StImageAttachmentScope.capture(conversation.assistantId, conversation.id, conversation, setOf(user.id))
    private val destination = StImageDestination(Uuid.random(), "synthetic-revision", "ST", "mcp__ST__store_memory_image")
    private val image = scope.images(conversation).single()
    private val original = StImageOriginal(byteArrayOf(1, 2, 3, 4), "image/png")

    private fun arguments(ref: String) = buildJsonObject {
        put("upload_ref", ref); put("mime_type", "image/png"); put("target_ref", "emotion://synthetic@1")
        put("description", "Synthetic pixels"); put("execution_ref", "synthetic-execution-binding")
    }

    @Test fun `reference schema hides bytes while retaining definitions and execution bindings`() {
        val properties = buildJsonObject {
            put("data_base64", buildJsonObject { put("type", "string") })
            put("upload_ref", buildJsonObject { put("type", "string") })
            put("execution_ref", buildJsonObject { put("type", "string") })
            put("mime_type", buildJsonObject { put("type", "string") })
        }
        val defs = buildJsonObject { put("binding", buildJsonObject { put("type", "string") }) }
        val originalSchema = InputSchema.Obj(properties, listOf("data_base64", "execution_ref", "mime_type"), defs,
            "https://json-schema.org/draft/2020-12/schema")
        val projected = stImageReferenceSchema(originalSchema) as InputSchema.Obj
        assertFalse("data_base64" in projected.properties)
        assertTrue(projected.required!!.containsAll(listOf("upload_ref", "execution_ref", "mime_type")))
        assertFalse("data_base64" in projected.required!!)
        assertEquals(defs, projected.defs)
        assertEquals(originalSchema.schema, projected.schema)
        assertEquals(properties["execution_ref"], projected.properties["execution_ref"])
        assertTrue("data_base64" in originalSchema.properties)
        assertEquals(JsonPrimitive("string"), projected.properties.getValue("upload_ref").jsonObject["type"])
        assertFalse(projected.properties.getValue("upload_ref").jsonObject.containsKey("anyOf"))
        assertFalse(projected.properties.getValue("upload_ref").jsonObject.containsKey("default"))
    }

    @Test fun `reservations are random stable bounded and reveal no local path`() {
        val registry = StMemoryImageAttachmentRegistry()
        val first = registry.reserve(scope, destination, image, original)
        val again = registry.reserve(scope.copy(), destination.copy(), image.copy(), original.copy())
        assertEquals(first, again)
        assertTrue(first.uploadRef.matches(Regex("stimgup_[0-9a-f]{64}")))
        assertNotEquals(first.uploadRef, StMemoryImageAttachmentRegistry().reserve(scope, destination, image, original).uploadRef)
        assertFalse(first.toString().contains("owned"))
        assertFalse(first.toString().contains("synthetic.png"))
    }

    @Test fun `list is local and returns only the current user image reservation`() = runTest {
        val registry = StMemoryImageAttachmentRegistry()
        var reads = 0
        val tool = createStImageListTool(scope, listOf(destination), registry, { conversation },
            { url -> assertEquals(image.url, url); reads++; original }, { true })
        val result = Json.parseToJsonElement((tool.execute(buildJsonObject {}).single() as UIMessagePart.Text).text).jsonObject
        assertEquals(1, reads)
        assertEquals(false, result["uploaded"]!!.jsonPrimitive.boolean)
        assertEquals(false, result["memory_written"]!!.jsonPrimitive.boolean)
        val listed = result["images"]!!.jsonArray.single().jsonObject
        assertEquals("prepared_not_uploaded", listed["state"]!!.jsonPrimitive.content)
        assertEquals(original.sha256, listed["sha256"]!!.jsonPrimitive.content)
        assertEquals(destination.storeTool, listed["store_tool"]!!.jsonPrimitive.content)
        assertFalse(result.toString().contains("file:"))
        assertFalse(tool.needsApproval(buildJsonObject {}))
    }

    @Test fun `latest user without a photo never picks an earlier image`() {
        val current = conversation.copy(messageNodes = conversation.messageNodes + UIMessage.user("New text only").toMessageNode())
        val currentScope = StImageAttachmentScope.capture(current.assistantId, current.id, current,
            current.currentMessages.map { it.id }.toSet())
        assertTrue(currentScope.images(current).isEmpty())
        try { scope.images(current); fail("old user scope must not follow a later message") } catch (_: IllegalArgumentException) { }
    }

    @Test fun `historical generation selects only its explicit source range and fails if branch advances`() {
        val later = UIMessage.user("A later unrelated photo").copy(parts = listOf(UIMessagePart.Image("file:///other.png")))
        val current = conversation.copy(messageNodes = conversation.messageNodes + later.toMessageNode())
        val historical = StImageAttachmentScope.capture(current.assistantId, current.id, current, setOf(user.id))
        assertEquals(listOf(image), historical.images(current))
        assertEquals(user.id, historical.userMessageId)
        assertEquals(later.id, historical.branchTailUserMessageId)
        val newer = current.copy(messageNodes = current.messageNodes + UIMessage.user("Next turn").toMessageNode())
        try { historical.images(newer); fail("new user revokes the old attachment scope") } catch (_: IllegalArgumentException) { }
        val switched = current.copy(messageNodes = listOf(later.toMessageNode()))
        try { historical.images(switched); fail("unselected old user is unavailable") } catch (_: IllegalArgumentException) { }
    }

    @Test fun `ref is bound to server revision assistant conversation message and exact original`() = runTest {
        val registry = StMemoryImageAttachmentRegistry()
        val prepared = registry.reserve(scope, destination, image, original)
        suspend fun denied(boundScope: StImageAttachmentScope = scope, target: StImageDestination = destination,
                           images: List<StChatImage> = listOf(image), bytes: StImageOriginal = original) {
            try { registry.resolve(prepared.uploadRef, boundScope, target, images) { bytes }; fail("must reject") }
            catch (_: IllegalArgumentException) { }
        }
        denied(scope.copy(assistantId = Uuid.random()))
        denied(scope.copy(conversationId = Uuid.random()))
        denied(scope.copy(userMessageId = Uuid.random()))
        denied(target = destination.copy(serverId = Uuid.random()))
        denied(target = destination.copy(revision = "changed"))
        denied(images = listOf(image.copy(partIndex = 99)))
        denied(images = listOf(image.copy(url = "file:///owned/upload/other.png")))
        denied(bytes = original.copy(bytes = byteArrayOf(4, 3, 2, 1)))
        denied(bytes = original.copy(mimeType = "image/jpeg"))
        val restored = registry.resolve(prepared.uploadRef, scope.copy(), destination.copy(), listOf(image.copy())) { original }
        assertArrayEquals(original.bytes, restored.bytes)
    }

    @Test fun `restart expiry and eviction safely reject old refs`() = runTest {
        var clock = 0L
        val registry = StMemoryImageAttachmentRegistry(now = { clock }, lifetimeMillis = 10, capacity = 1)
        val first = registry.reserve(scope, destination, image, original)
        suspend fun unknown(source: StMemoryImageAttachmentRegistry, ref: String) {
            try { source.resolve(ref, scope, destination, listOf(image)) { original }; fail("must reject") }
            catch (_: IllegalStateException) { }
        }
        unknown(StMemoryImageAttachmentRegistry(), first.uploadRef)
        registry.reserve(scope, destination, image, original.copy(bytes = byteArrayOf(9)))
        unknown(registry, first.uploadRef)
        val current = registry.reserve(scope, destination, image, original)
        clock = 11
        unknown(registry, current.uploadRef)
    }

    @Test fun `upload stages bytes then forwards the identical model arguments including execution binding`() = runTest {
        val registry = StMemoryImageAttachmentRegistry()
        val ref = registry.reserve(scope, destination, image, original).uploadRef
        val args = arguments(ref)
        val calls = mutableListOf<String>()
        val base = Tool("mcp__ST__store_memory_image", "Synthetic", execute = {
            assertSame(args, it); calls += "store"; listOf(UIMessagePart.Text("stored"))
        })
        val wrapped = wrapStImageUploadTool(base, "store_memory_image", scope, destination, registry,
            { conversation }, { original }, stage = { actualRef, actual ->
                assertEquals(ref, actualRef); assertArrayEquals(original.bytes, actual.bytes); calls += "stage"
            })
        wrapped.execute(args)
        assertEquals(listOf("stage", "store"), calls)
        assertEquals("synthetic-execution-binding", args["execution_ref"]!!.jsonPrimitive.content)
        assertFalse(args.containsKey("data_base64"))
    }

    @Test fun `manage wrapper preserves outer and inner execution fields and leaves unrelated actions alone`() = runTest {
        val registry = StMemoryImageAttachmentRegistry()
        val ref = registry.reserve(scope, destination, image, original).uploadRef
        val args = buildJsonObject {
            put("action", "store_memory_image"); put("arguments", arguments(ref)); put("execution_ref", "outer-binding")
        }
        var staged = 0
        var actual: JsonElement? = null
        val base = Tool("mcp__ST__stbrain_manage", "Synthetic", execute = { actual = it; listOf(UIMessagePart.Text("ok")) })
        val wrapped = wrapStImageUploadTool(base, "stbrain_manage", scope, destination, registry,
            { conversation }, { original }, stage = { _, _ -> staged++ })
        wrapped.execute(args)
        assertSame(args, actual); assertEquals(1, staged)
        val other = buildJsonObject { put("action", "list_memory_images"); put("arguments", buildJsonObject {}) }
        wrapped.execute(other)
        assertSame(other, actual); assertEquals(1, staged)
    }

    @Test fun `staging failure changed source and model supplied base64 never call store`() = runTest {
        val registry = StMemoryImageAttachmentRegistry()
        val ref = registry.reserve(scope, destination, image, original).uploadRef
        var stores = 0
        var stages = 0
        val base = Tool("store", "Synthetic", execute = { stores++; listOf(UIMessagePart.Text("unexpected")) })
        val wrapped = wrapStImageUploadTool(base, "store_memory_image", scope, destination, registry,
            { conversation }, { original }, stage = { _, _ -> stages++; error("synthetic stage failure") })
        try { wrapped.execute(arguments(ref)); fail("must fail") } catch (_: IllegalStateException) { }
        assertEquals(1, stages); assertEquals(0, stores)
        val injected = JsonObject(arguments(ref) + ("data_base64" to JsonPrimitive("model-invented")))
        try { wrapped.execute(injected); fail("must reject") } catch (_: IllegalArgumentException) { }
        assertEquals(1, stages); assertEquals(0, stores)
        val changed = wrapStImageUploadTool(base, "store_memory_image", scope, destination, registry,
            { conversation.copy(assistantId = Uuid.random()) }, { original }, stage = { _, _ -> stages++ })
        try { changed.execute(arguments(ref)); fail("must reject") } catch (_: IllegalArgumentException) { }
        assertEquals(1, stages); assertEquals(0, stores)
    }

    @Test fun `revoked target after staging prevents final dispatch`() = runTest {
        val registry = StMemoryImageAttachmentRegistry()
        val ref = registry.reserve(scope, destination, image, original).uploadRef
        var current = true
        var stores = 0
        val base = Tool("store", "Synthetic", isApprovalCurrent = { current }, execute = { stores++; emptyList() })
        val wrapped = wrapStImageUploadTool(base, "store_memory_image", scope, destination, registry,
            { conversation }, { original }, stage = { _, _ -> current = false })
        try { wrapped.execute(arguments(ref)); fail("must reject") } catch (_: IllegalStateException) { }
        assertEquals(0, stores)
    }

    @Test fun `image bytes changed during stage prevents final dispatch`() = runTest {
        val registry = StMemoryImageAttachmentRegistry()
        val ref = registry.reserve(scope, destination, image, original).uploadRef
        var bytes = original
        var stores = 0
        val base = Tool("store", "Synthetic", execute = { stores++; emptyList() })
        val wrapped = wrapStImageUploadTool(base, "store_memory_image", scope, destination, registry,
            { conversation }, { bytes }, stage = { _, _ -> bytes = original.copy(bytes = byteArrayOf(9, 8, 7, 6)) })
        try { wrapped.execute(arguments(ref)); fail("must reject changed source") } catch (_: IllegalArgumentException) { }
        assertEquals(0, stores)
    }
}
