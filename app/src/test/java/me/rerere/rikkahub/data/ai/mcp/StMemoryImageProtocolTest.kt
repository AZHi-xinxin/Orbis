package me.rerere.rikkahub.data.ai.mcp

import kotlinx.serialization.json.*
import me.rerere.ai.core.InputSchema
import org.junit.Assert.*
import org.junit.Test
import kotlin.io.encoding.Base64

class StMemoryImageProtocolTest {
    private val ref = "stimgup_" + "a".repeat(64)
    private val bytes = Base64.decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aX1sAAAAASUVORK5CYII=")
    private val hash = StMemoryImageProtocol.sha256(bytes)
    private val request = buildJsonObject {
        put("target_ref", "emotion://test@1"); put("image_ref", "image://test@1"); put("include_data", true)
    }
    private fun receipt() = buildJsonObject {
        put("contract", "memory-image-upload/1"); put("decision", "staged")
        put("upload_ref", ref); put("mime_type", "image/png"); put("sha256", hash)
        put("byte_count", bytes.size); put("width", 1); put("height", 1)
        put("expires_in_seconds", 900); put("memory_written", false); put("write_authority_granted", false)
    }
    private fun read() = buildJsonObject {
        put("contract_version", "memory-images/1"); put("decision", "read"); put("active", true)
        put("target_ref", request.getValue("target_ref")); put("image_ref", request.getValue("image_ref"))
        put("mime_type", "image/png"); put("sha256", hash); put("byte_count", bytes.size)
        put("width", 1); put("height", 1); put("data_uri", "data:image/png;base64,${Base64.encode(bytes)}")
    }
    private fun JsonObject.change(key: String, value: JsonElement) = JsonObject(this + (key to value))
    private fun rejected(block: () -> Unit) {
        try { block(); fail("must reject") } catch (_: IllegalArgumentException) { }
    }

    @Test fun uploadContainsExactOriginalBytesAndNoFinalWriteParameters() {
        val args = StMemoryImageProtocol.stageArguments(ref, "image/png", bytes, hash)
        assertArrayEquals(bytes, Base64.decode(args.getValue("data_base64").jsonPrimitive.content))
        assertFalse(args.containsKey("target_ref")); assertFalse(args.containsKey("execution_ref"))
    }
    @Test fun uploadRejectsMismatchedBytesAndOversize() {
        rejected { StMemoryImageProtocol.stageArguments(ref, "image/png", bytes + 1.toByte(), hash) }
        rejected { StMemoryImageProtocol.stageArguments(ref, "text/plain", bytes, hash) }
        rejected { StMemoryImageProtocol.stageArguments("guessable", "image/png", bytes, hash) }
        val huge = ByteArray(StMemoryImageProtocol.MAX_BYTES + 1)
        rejected { StMemoryImageProtocol.stageArguments(ref, "image/png", huge, hash) }
    }
    @Test fun receiptMustConfirmExactTransportOnly() {
        StMemoryImageProtocol.validateStageReceipt(receipt().toString(), ref, "image/png", bytes.size, hash)
        listOf("upload_ref" to JsonPrimitive("different"), "sha256" to JsonPrimitive("b".repeat(64)),
            "byte_count" to JsonPrimitive(bytes.size + 1), "expires_in_seconds" to JsonPrimitive(0),
            "write_authority_granted" to JsonPrimitive(true), "memory_written" to JsonPrimitive(true),
            "width" to JsonPrimitive(30000), "contract" to JsonPrimitive("other")) .forEach { (key, value) ->
            rejected { StMemoryImageProtocol.validateStageReceipt(receipt().change(key, value).toString(), ref, "image/png", bytes.size, hash) }
        }
    }
    @Test fun readRequiresExplicitActionAndBoolean() {
        assertEquals(request, StMemoryImageProtocol.readArguments("read_memory_image", request))
        assertNull(StMemoryImageProtocol.readArguments("other", request))
        assertNull(StMemoryImageProtocol.readArguments("read_memory_image", request.change("include_data", JsonPrimitive("true"))))
        assertEquals(request, StMemoryImageProtocol.readArguments("stbrain_manage", buildJsonObject {
            put("action", "read_memory_image"); put("arguments", request)
        }))
    }
    @Test fun originalReadMatchesHashAndStripsEncodedDataFromMetadata() {
        val decoded = StMemoryImageProtocol.decodeRead(read().toString(), request)!!
        assertArrayEquals(bytes, decoded.bytes)
        assertEquals(hash, StMemoryImageProtocol.sha256(decoded.bytes))
        assertFalse(decoded.metadata.containsKey("data_uri"))
        assertEquals("image/png", decoded.mimeType)
    }
    @Test fun readRejectsDifferentTargetImageAndTamper() {
        listOf("target_ref" to JsonPrimitive("emotion://another@1"), "image_ref" to JsonPrimitive("image://another@1"),
            "sha256" to JsonPrimitive("b".repeat(64)), "byte_count" to JsonPrimitive(0),
            "mime_type" to JsonPrimitive("image/jpeg"), "active" to JsonPrimitive(false),
            "data_uri" to JsonPrimitive("https://example.invalid/image.png"),
            "data_uri" to JsonPrimitive("data:image/png;base64,?broken?")) .forEach { (key, value) ->
            rejected { StMemoryImageProtocol.decodeRead(read().change(key, value).toString(), request) }
        }
    }
    @Test fun metadataOnlyAndNormalErrorsAreNotImages() {
        assertNull(StMemoryImageProtocol.decodeRead("{\"decision\":\"reject\"}", request))
        assertNull(StMemoryImageProtocol.decodeRead(JsonObject(read().filterKeys { it != "data_uri" }).toString(), request))
    }
    @Test fun originalImageRemainsReadableAfterMemoryRevisionButNeverAcrossModules() {
        val newer = request.change("target_ref", JsonPrimitive("emotion://test@2"))
        assertArrayEquals(bytes, StMemoryImageProtocol.decodeRead(read().toString(), newer)!!.bytes)
        rejected { StMemoryImageProtocol.decodeRead(read().toString(), request.change("target_ref", JsonPrimitive("plan://test@1"))) }
        rejected { StMemoryImageProtocol.decodeRead(read().toString(), request.change("target_ref", JsonPrimitive("emotion://test@0"))) }
    }
    @Test fun capabilityRequiresVersionAndPublicStoreOrCompactTool() {
        fun stage(version: String) = McpTool(name = "stage_memory_image_upload", inputSchema = InputSchema.Obj(
            properties = buildJsonObject { putJsonObject("protocol") { putJsonArray("enum") { add(version) } } }
        ))
        val compact = McpTool(name = "stbrain_manage")
        assertTrue(StMemoryImageProtocol.supports(listOf(stage("memory-image-upload/1"), compact)))
        assertFalse(StMemoryImageProtocol.supports(listOf(stage("memory-image-upload/2"), compact)))
        assertFalse(StMemoryImageProtocol.supports(listOf(compact)))
        assertFalse(StMemoryImageProtocol.supports(listOf(stage("memory-image-upload/1"))))
        assertFalse(StMemoryImageProtocol.supports(listOf(stage("memory-image-upload/1").copy(enable = false), compact)))
        assertFalse(StMemoryImageProtocol.isHostUploadTool(McpTool(name = "stage_memory_image_upload")))
        assertFalse(StMemoryImageProtocol.isHostUploadTool(stage("unrelated/1")))
    }
}
