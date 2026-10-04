package me.rerere.rikkahub.data.sync.importer

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Conversation
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.Instant
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.uuid.Uuid

/** Every byte here is synthetic. Never add a user's official export as a fixture. */
class DeepSeekImporterTest {
    @get:Rule val temporary = TemporaryFolder()
    private val assistant = Uuid.parse("11111111-1111-4111-8111-111111111111")
    private val time = "2025-01-02T03:04:05.123456+00:00"

    private fun fragment(type: String, body: String) = buildJsonObject { put("type", type); put("content", body) }
    private fun node(id: String, parent: String?, children: List<String>, fragments: List<JsonObject>?): JsonObject = buildJsonObject {
        put("id", id); put("parent", parent?.let(::JsonPrimitive) ?: JsonNull)
        put("children", JsonArray(children.map(::JsonPrimitive)))
        put("message", if (fragments == null) JsonNull else buildJsonObject {
            put("model", "deepseek-synthetic"); put("inserted_at", time); put("fragments", JsonArray(fragments))
        })
    }
    private fun conversation(id: String = "c", branch: Boolean = false, body: String = "  exact\r\n正文🙂  ", current: String? = null): JsonObject = buildJsonObject {
        put("id", id); put("title", "  synthetic title  "); put("inserted_at", time); put("updated_at", time)
        current?.let { put("current_node", it) }
        put("mapping", buildJsonObject {
            put("root", node("root", null, listOf("u"), null))
            put("u", node("u", "root", if (branch) listOf("a", "b") else listOf("a"), listOf(fragment("REQUEST", body))))
            put("a", node("a", "u", emptyList(), listOf(fragment("THINK", "  reasoning\n "), fragment("RESPONSE", "answer"))))
            if (branch) put("b", node("b", "u", emptyList(), listOf(fragment("RESPONSE", "alternate"))))
        })
    }
    private fun zip(vararg conversations: JsonObject, extras: Map<String, ByteArray> = emptyMap()): File = rawZip(
        JsonArray(conversations.toList()).toString().toByteArray(), extras)
    private fun rawZip(json: ByteArray, extras: Map<String, ByteArray> = emptyMap()): File {
        val file = temporary.newFile("fixture-${Uuid.random()}.zip")
        ZipOutputStream(file.outputStream()).use { zip ->
            (mapOf("conversations.json" to json) + extras).forEach { (name, data) ->
                zip.putNextEntry(ZipEntry(name)); zip.write(data); zip.closeEntry()
            }
        }
        return file
    }
    private fun parse(value: JsonObject) = DeepSeekParser.parse(JsonArray(listOf(value))).conversations.single()

    @Test fun streamsAValidArchiveAboveTheOld64MiBLimitWithoutBuildingOneLargeString() {
        val file = temporary.newFile("synthetic-large-stream.zip")
        ZipOutputStream(file.outputStream()).use { output ->
            output.putNextEntry(ZipEntry("conversations.json"))
            output.write(JsonArray(listOf(conversation())).toString().toByteArray())
            val padding = ByteArray(64 * 1024) { ' '.code.toByte() }
            repeat(1040) { output.write(padding) } // 65 MiB of legal trailing whitespace, streamed.
            output.closeEntry()
        }
        assertEquals(1, DeepSeekArchive.inspect(file).conversations.size)
    }
    private class Sink : DeepSeekImportSink {
        val saved = linkedMapOf<Uuid, Conversation>()
        override suspend fun exists(id: Uuid) = id in saved
        override suspend fun insert(conversation: Conversation): Boolean {
            if (conversation.id in saved) return false
            saved[conversation.id] = conversation
            return true
        }
    }

    @Test fun inspectPreservesBoundariesAndLabelsTheDefaultBranch() {
        val result = DeepSeekArchive.inspect(zip(conversation("first", true), conversation("second")))
        assertEquals(2, result.conversations.size)
        val first = result.conversations.first()
        assertEquals("  synthetic title  ", first.title)
        assertEquals(4, first.totalNodes)
        assertEquals(3, first.messageCount)
        assertEquals(1, first.branchPointCount)
        assertEquals(2, first.branches.size)
        assertTrue(first.branches.all { it.messageCount == 2 })
        assertEquals("newest_leaf", first.defaultSelectionReason)
        assertEquals("b", first.defaultLeafId)
    }

    @Test fun explicitCurrentNodeWinsOverTheDefaultLeaf() {
        val preview = DeepSeekArchive.inspect(zip(conversation(branch = true, current = "a"))).conversations.single()
        assertEquals("a", preview.defaultLeafId)
        assertEquals("source_current_node", preview.defaultSelectionReason)
    }

    @Test fun selectedPathKeepsExactBodiesReasoningRolesAndTime() {
        val source = parse(conversation(branch = true))
        val converted = DeepSeekChatImporter.convert(source, "a", assistant).conversation
        assertEquals(2, converted.messageNodes.size)
        assertEquals(listOf(MessageRole.USER, MessageRole.ASSISTANT), converted.currentMessages.map { it.role })
        assertEquals("  exact\r\n正文🙂  ", (converted.currentMessages.first().parts.single() as UIMessagePart.Text).text)
        val reasoning = converted.currentMessages.last().parts.first() as UIMessagePart.Reasoning
        assertEquals("  reasoning\n ", reasoning.reasoning)
        assertEquals("2025-01-02T03:04:05.123456Z", reasoning.createdAt.toString())
        assertEquals(Instant.parse("2025-01-02T03:04:05.123456Z"), converted.createAt)
        assertTrue(converted.currentMessages.none { it.toText().contains("alternate") })
        assertNull(converted.customSystemPrompt)
        assertTrue(converted.currentMessages.all { it.getTools().isEmpty() && it.modelId == null && it.orbisEvent == null })
    }

    @Test fun importIsAdditiveIdempotentAndAnotherBranchRequiresAnotherSelection() = runBlocking {
        val sink = Sink()
        val file = zip(conversation(branch = true))
        val originalBytes = file.readBytes()
        val importer = DeepSeekChatImporter(sink)
        assertEquals(1, importer.import(file, assistant, mapOf("c" to "a")).imported)
        val first = sink.saved.values.single()
        assertEquals(1, importer.import(file, Uuid.random(), mapOf("c" to "a")).skipped)
        assertEquals(first, sink.saved.values.single())
        assertEquals(1, importer.import(file, assistant, mapOf("c" to "b")).imported)
        assertEquals(2, sink.saved.size)
        assertTrue(sink.saved.values.all { it.messageNodes.size == 2 })
        assertArrayEquals(originalBytes, file.readBytes())
    }

    @Test fun identityIsNamespacedAndUnambiguous() {
        assertNotEquals(deepSeekImportId("conversation", "a/b", "c"), deepSeekImportId("conversation", "a", "b/c"))
        assertNotEquals(deepSeekImportId("conversation", "a", "b"), deepSeekImportId("message", "a", "b"))
    }

    @Test fun onlyExplicitlySelectedConversationsAreImported() = runBlocking {
        val sink = Sink()
        val result = DeepSeekChatImporter(sink).import(zip(conversation("one"), conversation("two")), assistant, mapOf("two" to "a"))
        assertEquals(1, result.imported)
        assertEquals(deepSeekImportId("conversation", "two", "a"), sink.saved.keys.single())
    }

    @Test fun unknownSelectionFailsBeforeAnyWrite() = runBlocking {
        val sink = Sink()
        try { DeepSeekChatImporter(sink).import(zip(conversation()), assistant, mapOf("c" to "missing")); fail() }
        catch (failure: DeepSeekImportException) { assertEquals(0, failure.partialResult.imported) }
        assertTrue(sink.saved.isEmpty())
    }

    @Test fun malformedLaterConversationPreflightPreventsPartialImport() = runBlocking {
        val sink = Sink()
        val bad = JsonObject(conversation("bad") - "mapping")
        try { DeepSeekChatImporter(sink).import(zip(conversation(), bad), assistant, mapOf("c" to "a")); fail() }
        catch (_: DeepSeekImportException) { }
        assertTrue(sink.saved.isEmpty())
    }

    @Test fun cancellationReportsCommittedConversationsAndCanBeResumed() = runBlocking {
        val sink = Sink()
        val file = zip(conversation("one"), conversation("two"))
        try {
            DeepSeekChatImporter(sink).import(file, assistant, mapOf("one" to "a", "two" to "a")) {
                if (it.completed == 1) throw CancellationException("synthetic cancellation")
            }
            fail()
        } catch (cancelled: DeepSeekImportCancelledException) {
            assertEquals(1, cancelled.partialResult.imported)
        }
        assertEquals(1, sink.saved.size)
        val resumed = DeepSeekChatImporter(sink).import(file, assistant, mapOf("one" to "a", "two" to "a"))
        assertEquals(1, resumed.imported); assertEquals(1, resumed.skipped)
    }

    @Test fun cancellationDuringInspectionDoesNotReturnPartialPreview() {
        var calls = 0
        try { DeepSeekArchive.inspect(zip(conversation())) { if (++calls >= 2) throw CancellationException() }; fail() }
        catch (_: CancellationException) { }
    }

    @Test fun oversizedSingleNodeFailsThatConversationWithoutTruncatingOrBlockingOtherChats() = runBlocking {
        val sink = Sink()
        val result = DeepSeekChatImporter(sink).import(zip(conversation("huge", body = "x".repeat(800_000)), conversation("normal")),
            assistant, mapOf("huge" to "a", "normal" to "a"))
        assertEquals(1, result.failed); assertEquals(1, result.imported)
        assertEquals("huge", result.failures.single().sourceId)
        assertEquals(1, sink.saved.size)
    }

    @Test fun longMillionCharacterChainAndForkStayOneSelectedConversation() {
        val count = 2_200
        val mapping = linkedMapOf<String, kotlinx.serialization.json.JsonElement>()
        mapping["root"] = node("root", null, listOf("n0"), null)
        repeat(count) { index ->
            val children = if (index == count - 1) listOf("alternate", "selected") else listOf("n${index + 1}")
            mapping["n$index"] = node("n$index", if (index == 0) "root" else "n${index - 1}", children,
                listOf(fragment(if (index % 2 == 0) "REQUEST" else "RESPONSE", "字".repeat(512))))
        }
        mapping["alternate"] = node("alternate", "n${count - 1}", emptyList(), listOf(fragment("RESPONSE", "not selected")))
        mapping["selected"] = node("selected", "n${count - 1}", emptyList(), listOf(fragment("RESPONSE", "selected")))
        val raw = JsonObject(conversation().toMutableMap().apply { put("mapping", JsonObject(mapping)) })
        val file = zip(raw)
        val preview = DeepSeekArchive.inspect(file).conversations.single()
        assertEquals(count + 2, preview.messageCount)
        assertEquals(2, preview.branches.size)
        val output = DeepSeekChatImporter.convert(parse(raw), "selected", assistant).conversation
        assertEquals(count + 1, output.messageNodes.size)
        assertTrue(output.currentMessages.sumOf { it.toText().length } > 1_000_000)
        assertEquals("selected", output.currentMessages.last().toText())
    }

    @Test fun staticFileAndSearchFragmentsNeverBecomeLiveToolsOrAttachments() {
        val file = buildJsonObject { put("type", "FILE"); put("files", buildJsonArray { add(buildJsonObject {
            put("file_id", "id"); put("file_name", "synthetic.txt"); put("file_size", 123)
        }) }) }
        val tool = buildJsonObject { put("type", "TOOL_OPEN"); put("content", "untrusted historical content")
            put("results", buildJsonArray { add(buildJsonObject { put("url", "https://example.invalid/no-download"); put("title", "synthetic") }) }) }
        val mapping = buildJsonObject {
            put("root", node("root", null, listOf("u"), null))
            put("u", node("u", "root", listOf("a"), listOf(file)))
            put("a", node("a", "u", emptyList(), listOf(tool)))
        }
        val raw = JsonObject(conversation().toMutableMap().apply { put("mapping", mapping) })
        val result = DeepSeekChatImporter.convert(parse(raw), "a", assistant)
        assertEquals(1, result.attachmentReferences)
        assertTrue(result.conversation.currentMessages.flatMap { it.parts }.all { it is UIMessagePart.Text })
        assertTrue(result.conversation.currentMessages.all { it.getTools().isEmpty() })
        assertEquals(MessageRole.USER, result.conversation.currentMessages.first().role)
        assertEquals("untrusted historical content", (result.conversation.currentMessages.last().parts.first() as UIMessagePart.Text).text)
    }

    @Test fun emptyInterruptedMessageIsNotDropped() {
        val mapping = buildJsonObject {
            put("root", node("root", null, listOf("u"), null))
            put("u", node("u", "root", listOf("a"), listOf(fragment("REQUEST", "request"))))
            put("a", node("a", "u", emptyList(), emptyList()))
        }
        val raw = JsonObject(conversation().toMutableMap().apply { put("mapping", mapping) })
        val messages = DeepSeekChatImporter.convert(parse(raw), "a", assistant).conversation.currentMessages
        assertEquals(2, messages.size); assertEquals(MessageRole.ASSISTANT, messages.last().role)
        assertEquals("", messages.last().toText())
    }

    @Test fun malformedGraphIsRejectedWithoutExposingBodyInError() {
        val original = conversation(body = "PRIVATE_SYNTHETIC_MARKER")
        val brokenMapping = JsonObject((original.getValue("mapping") as JsonObject) - "u")
        try { parse(JsonObject(original.toMutableMap().apply { put("mapping", brokenMapping) })); fail() }
        catch (failure: IllegalArgumentException) { assertFalse(failure.message.orEmpty().contains("PRIVATE_SYNTHETIC_MARKER")) }
    }

    @Test fun duplicateJsonKeysCannotDiscardABranch() {
        try { DeepSeekStrictJson.parse("{\"mapping\":{},\"mapping\":{}}"); fail() }
        catch (_: IllegalArgumentException) { }
    }

    @Test fun invalidJsonTokensAndExcessiveDepthAreRejected() {
        for (text in listOf("[1,]", "{\"x\":01}", "{\"x\":NaN}", "[] trailing", "[".repeat(50) + "0" + "]".repeat(50))) {
            try { DeepSeekStrictJson.parse(text); fail("accepted invalid synthetic JSON") }
            catch (_: IllegalArgumentException) { }
        }
    }

    @Test fun unicodeSurrogatePairsArePreservedAndBrokenEscapesRejected() {
        assertEquals(JsonPrimitive("🙂"), DeepSeekStrictJson.parse("\"\\uD83D\\uDE42\""))
        for (text in listOf("\"\\uD800\"", "\"\\uDC00\"", "\"\\uD800x\"")) {
            try { DeepSeekStrictJson.parse(text); fail() }
            catch (_: IllegalArgumentException) { }
        }
    }

    @Test fun traversalAndUnexpectedZipEntriesAreRejectedWithoutExtraction() {
        for (path in listOf("../outside.json", "/absolute.json", "folder/conversations.json", "attachment.png", "C:\\outside")) {
            try { DeepSeekArchive.inspect(zip(conversation(), extras = mapOf(path to byteArrayOf(1)))); fail() }
            catch (_: IllegalArgumentException) { }
        }
    }

    @Test fun userProfileIsNotParsedAndInvalidUtf8IsRejected() {
        assertEquals(1, DeepSeekArchive.inspect(zip(conversation(), extras = mapOf("user.json" to byteArrayOf(0xFF.toByte())))).conversations.size)
        try { DeepSeekArchive.inspect(rawZip(byteArrayOf(0xFF.toByte()))); fail() }
        catch (_: IllegalArgumentException) { }
    }

    @Test fun sourceGraphCycleOrDuplicateConversationFailsClosed() {
        try { DeepSeekArchive.inspect(zip(conversation(), conversation())); fail() }
        catch (_: IllegalArgumentException) { }
        val mapping = buildJsonObject {
            put("root", node("root", null, emptyList(), null))
            put("a", node("a", "b", listOf("b"), listOf(fragment("RESPONSE", "a"))))
            put("b", node("b", "a", listOf("a"), listOf(fragment("RESPONSE", "b"))))
        }
        try { parse(JsonObject(conversation().toMutableMap().apply { put("mapping", mapping) })); fail() }
        catch (_: IllegalArgumentException) { }
    }
}
