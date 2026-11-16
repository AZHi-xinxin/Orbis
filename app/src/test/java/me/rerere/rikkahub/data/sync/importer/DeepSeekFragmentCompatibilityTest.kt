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

    @Test fun futureFragmentsRemainInertAndCompleteThroughPreviewAndImport() = runBlocking {
        val future = buildJsonObject { put("type", "TOOL_FUTURE"); put("content", buildJsonObject { put("nested", "exact") }); put("ratio", 0.75) }
        val partial = buildJsonObject { put("type", "PARTIAL_FILE_READ"); put("ratio", 0.25) }
        val file = zip(conversation(listOf(future, partial)))
        assertEquals(1, DeepSeekArchive.inspect(file).conversations.size)
        val sink = Sink()
        assertEquals(1, DeepSeekChatImporter(sink).import(file, assistant, mapOf("c" to "tool")).imported)
        val message = sink.saved.values.single().currentMessages.last()
        assertTrue(message.getTools().isEmpty())
        assertEquals(future, DeepSeekStrictJson.parse((message.parts[0] as UIMessagePart.Text).text.substringAfter('\n')))
        assertEquals(partial, DeepSeekStrictJson.parse((message.parts[1] as UIMessagePart.Text).text.substringAfter('\n')))
    }

    @Test fun findDoesNotRelaxMixedRoleValidation() {
        for (userFragment in listOf(fragment("REQUEST", "question"), fragment("FILE"))) {
            assertThrows(IllegalArgumentException::class.java) {
                parse(conversation(listOf(userFragment, fragment("TOOL_FIND"))))
            }
        }
    }

    @Test fun changedOptionalToolPayloadIsPreservedInsteadOfBlockingTheArchive() {
        val fragment = buildJsonObject { put("type", "TOOL_FIND"); put("content", JsonNull); put("results", "new shape") }
        val message = DeepSeekChatImporter.convert(parse(conversation(listOf(fragment))), "tool", assistant).conversation.currentMessages.last()
        assertEquals(fragment, DeepSeekStrictJson.parse((message.parts.single() as UIMessagePart.Text).text.substringAfter('\n')))
        assertTrue(message.getTools().isEmpty())
    }
    @Test fun legacyNullTitleAndNullModelDoNotRejectValidHistory() {
        val raw=conversation(listOf(fragment("RESPONSE","retained")))
        val mapping=raw["mapping"] as JsonObject
        val node=mapping["tool"] as JsonObject
        val message=node["message"] as JsonObject
        val changed=JsonObject(raw+mapOf("title" to JsonNull,"mapping" to JsonObject(mapping+
            ("tool" to JsonObject(node+("message" to JsonObject(message+("model" to JsonNull))))))))
        val c=parse(changed)
        assertEquals("未命名会话",c.title);assertEquals("",c.nodes["tool"]!!.message!!.sourceModel)
        assertEquals("retained",c.nodes["tool"]!!.message!!.fragments.single().content)
        assertEquals(5,c.nodes.size)
    }

}
