package me.rerere.rikkahub.data.sync.importer

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
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.uuid.Uuid

/** Synthetic shapes only: never commit a private export or copy its text into a fixture. */
class DeepSeekFragmentCompatibilityTest {
    @get:Rule val temporary = TemporaryFolder()
    private val assistant = Uuid.parse("11111111-1111-4111-8111-111111111111")
    private val time = "2025-01-02T03:04:05+00:00"
    private val unsupportedMessage = "DeepSeek 导出包包含尚未支持的消息片段类型；已停止导入，没有忽略记录。请保留原文件以便适配。"

    private fun fragment(type: String, content: String? = null) = buildJsonObject {
        put("type", type)
        content?.let { put("content", it) }
    }

    private fun node(id: String, parent: String?, children: List<String>, fragments: List<JsonObject>?) = buildJsonObject {
        put("id", id)
        put("parent", parent?.let(::JsonPrimitive) ?: JsonNull)
        put("children", JsonArray(children.map(::JsonPrimitive)))
        put("message", fragments?.let {
            buildJsonObject {
                put("model", "synthetic")
                put("inserted_at", time)
                put("fragments", JsonArray(it))
            }
        } ?: JsonNull)
    }

    // The tool-only message follows another assistant message: role alternation would be wrong.
    private fun conversation(fragments: List<JsonObject>, id: String = "c") = buildJsonObject {
        put("id", id)
        put("title", "synthetic")
        put("inserted_at", time)
        put("updated_at", time)
        put("current_node", "tool")
        put("mapping", buildJsonObject {
            put("root", node("root", null, listOf("u"), null))
            put("u", node("u", "root", listOf("a"), listOf(fragment("REQUEST", "question"))))
            put("a", node("a", "u", listOf("tool", "alternate"), listOf(fragment("RESPONSE", "before tool"))))
            put("tool", node("tool", "a", emptyList(), fragments))
            put("alternate", node("alternate", "a", emptyList(), listOf(fragment("RESPONSE", "other branch"))))
        })
    }

    private fun parse(raw: JsonObject) = DeepSeekParser.parse(JsonArray(listOf(raw))).conversations.single()

    private fun zip(vararg conversations: JsonObject): File = temporary.newFile().also { file ->
        ZipOutputStream(file.outputStream()).use { output ->
            output.putNextEntry(ZipEntry("conversations.json"))
            output.write(JsonArray(conversations.toList()).toString().toByteArray(Charsets.UTF_8))
            output.closeEntry()
        }
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

    @Test fun typeOnlyFindIsPreservedAsAnAssistantHistoryRecord() {
        val source = parse(conversation(listOf(fragment("TOOL_FIND"))))
        val messages = DeepSeekChatImporter.convert(source, "tool", assistant).conversation.currentMessages
        assertEquals(listOf(MessageRole.USER, MessageRole.ASSISTANT, MessageRole.ASSISTANT), messages.map { it.role })
        assertEquals("[历史检索记录；未执行]\n{\"type\":\"TOOL_FIND\"}", messages.last().toText())
        assertTrue(messages.all { it.getTools().isEmpty() && it.modelId == null && it.orbisEvent == null })
    }

    @Test fun findContentAndMetadataStayExactAndInert() {
        val body = "  synthetic historical text\r\n🙂  "
        val tool = buildJsonObject {
            put("type", "TOOL_FIND")
            put("content", body)
            put("query", "synthetic phrase")
            put("results", buildJsonArray {
                add(buildJsonObject { put("url", "https://example.invalid/no-fetch"); put("title", "synthetic result") })
            })
            put("extra", buildJsonObject { put("kept", true) })
        }
        val converted = DeepSeekChatImporter.convert(parse(conversation(listOf(tool))), "tool", assistant)
        val message = converted.conversation.currentMessages.last()
        assertEquals(MessageRole.ASSISTANT, message.role)
        assertEquals(2, message.parts.size)
        assertTrue(message.parts.all { it is UIMessagePart.Text })
        assertEquals(body, (message.parts.first() as UIMessagePart.Text).text)
        val record = (message.parts.last() as UIMessagePart.Text).text.substringAfter('\n')
        assertEquals(JsonObject(tool - "content"), DeepSeekStrictJson.parse(record))
        assertTrue(message.getTools().isEmpty())
        assertEquals(0, converted.attachmentReferences)
    }

    @Test fun findAlongsideReasoningAndResponseKeepsAllPartsInOrder() {
        val source = parse(conversation(listOf(fragment("THINK", "thought"), fragment("TOOL_FIND"), fragment("RESPONSE", "answer"))))
        val message = DeepSeekChatImporter.convert(source, "tool", assistant).conversation.currentMessages.last()
        assertEquals(MessageRole.ASSISTANT, message.role)
        assertEquals(3, message.parts.size)
        assertEquals("thought", (message.parts[0] as UIMessagePart.Reasoning).reasoning)
        assertTrue((message.parts[1] as UIMessagePart.Text).text.contains("TOOL_FIND"))
        assertEquals("answer", (message.parts[2] as UIMessagePart.Text).text)
        assertTrue(message.getTools().isEmpty())
    }

    @Test fun allKnownAssistantFragmentsAreClassifiedWithoutAlternatingRoles() {
        for (type in listOf("RESPONSE", "THINK", "SEARCH", "TOOL_SEARCH", "TOOL_OPEN", "TOOL_FIND")) {
            val source = parse(conversation(listOf(fragment(type, if (type in setOf("RESPONSE", "THINK")) "text" else null))))
            assertEquals(type, MessageRole.ASSISTANT, source.nodes.getValue("tool").message!!.role)
        }
    }

    @Test fun previewAndImportPreserveSelectionExistingChatsAndDuplicateDetection() = runBlocking {
        val file = zip(conversation(listOf(fragment("TOOL_FIND"))))
        val original = file.readBytes()
        val preview = DeepSeekArchive.inspect(file).conversations.single()
        assertEquals(4, preview.messageCount)
        assertEquals(setOf("tool", "alternate"), preview.branches.map { it.leafId }.toSet())
        assertEquals("tool", preview.defaultLeafId)
        val sink = Sink()
        val existing = DeepSeekChatImporter.convert(parse(conversation(listOf(fragment("TOOL_OPEN")), "existing")), "tool", assistant).conversation
        sink.saved[existing.id] = existing
        val importer = DeepSeekChatImporter(sink)
        val result = importer.import(file, assistant, mapOf("c" to "tool"))
        assertEquals(1, result.imported)
        assertEquals(3, result.messages)
        assertEquals(0, result.failed)
        val imported = sink.saved.getValue(deepSeekImportId("conversation", "c", "tool"))
        assertTrue(imported.currentMessages.last().toText().contains("TOOL_FIND"))
        assertTrue(imported.currentMessages.none { it.toText().contains("other branch") })
        assertEquals(1, importer.import(file, assistant, mapOf("c" to "tool")).skipped)
        assertEquals(1, importer.import(file, assistant, mapOf("c" to "alternate")).imported)
        assertEquals(3, sink.saved.size)
        assertSame(existing, sink.saved[existing.id])
        assertArrayEquals(original, file.readBytes())
    }

    @Test fun unknownTypesHaveASafeSpecificErrorThroughParseAndPreview() {
        for (type in listOf("TOOL_FUTURE", "PRIVATE_SYNTHETIC_MARKER\n{\"secret\":true}", "")) {
            val raw = conversation(listOf(fragment(type, "PRIVATE_SYNTHETIC_BODY")))
            val parserError = assertThrows(IllegalArgumentException::class.java) { parse(raw) }
            val previewError = assertThrows(IllegalArgumentException::class.java) { DeepSeekArchive.inspect(zip(raw)) }
            assertEquals(unsupportedMessage, parserError.message)
            assertEquals(unsupportedMessage, ArchiveCapacity.publicError(previewError))
            assertEquals(ArchiveFailure.FORMAT, ArchiveCapacity.reasonOf(previewError))
            assertNull(previewError.cause)
        }
    }

    @Test fun unknownToolInLaterWindowPreventsAllWritesAndPreservesTheDiagnostic() = runBlocking {
        val sink = Sink()
        val file = zip(conversation(listOf(fragment("TOOL_FIND"))), conversation(listOf(fragment("TOOL_FUTURE")), "bad"))
        try {
            DeepSeekChatImporter(sink).import(file, assistant, mapOf("c" to "tool"))
            fail("Unknown fragments must not be silently discarded")
        } catch (failure: DeepSeekImportException) {
            assertEquals(0, failure.partialResult.imported)
            assertEquals(unsupportedMessage, failure.publicDetail)
            assertEquals(ArchiveFailure.FORMAT, failure.failureReason)
        }
        assertTrue(sink.saved.isEmpty())
    }

    @Test fun findDoesNotRelaxMixedRoleValidation() {
        for (userFragment in listOf(fragment("REQUEST", "question"), fragment("FILE"))) {
            assertThrows(IllegalArgumentException::class.java) {
                parse(conversation(listOf(userFragment, fragment("TOOL_FIND"))))
            }
        }
    }

    @Test fun malformedKnownToolFieldsStillFailValidation() {
        val malformed = listOf(
            buildJsonObject { put("type", "TOOL_FIND"); put("content", JsonNull) },
            buildJsonObject { put("type", "TOOL_FIND"); put("results", JsonPrimitive("not an array")) },
            buildJsonObject { put("type", "TOOL_FIND"); put("results", buildJsonArray { add(buildJsonObject { put("url", "missing title") }) }) },
        )
        for (tool in malformed) {
            assertThrows(IllegalArgumentException::class.java) { parse(conversation(listOf(tool))) }
        }
    }
}
