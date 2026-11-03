package me.rerere.rikkahub.data.sync.importer

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.uuid.Uuid

class OperitCompatibilityTest {
    @get:Rule val temporary = TemporaryFolder()
    private val assistant = Uuid.parse("11111111-1111-4111-8111-111111111111")
    private fun section(type: String, body: String, key: String = "content") = buildJsonObject {
        put("type", type); put(key, body)
    }
    private fun body(sections: List<JsonObject>? = null, text: String? = null,
        sender: String = "ai", selected: Int = 0) = buildJsonObject {
        put("sender", sender); put("timestamp", 123456789L); put("selectedVariantIndex", selected)
        if (sections != null) put("sections", JsonArray(sections))
        if (text != null) put("content", text)
    }
    private fun row(base: JsonObject, variants: List<JsonObject> = emptyList()) = buildJsonObject {
        put("baseMessage", base); put("variants", JsonArray(variants))
    }
    private fun file(rows: List<JsonObject>, version: Int = 3, lateHeader: Boolean = false,
        extra: JsonObject = buildJsonObject { put("folders", JsonArray(emptyList())) }): File {
        val chats = JsonArray(listOf(buildJsonObject {
            put("id", "compat-chat"); put("title", "synthetic")
            put("createdAt", "2026-11-03T12:00:00"); put("updatedAt", "2026-11-03T12:00:00")
            put("messages", JsonArray(rows))
        }))
        val root = buildJsonObject {
            if (lateHeader) put("chats", chats)
            put("archiveType", "operit_chat_archive"); put("formatVersion", version)
            extra.forEach { (key, value) -> put(key, value) }
            if (!lateHeader) put("chats", chats)
        }
        return temporary.newFile().also { it.writeText(root.toString()) }
    }
    private fun converted(file: File): Conversation {
        OperitChatArchive.inspect(file)
        return OperitChatArchive.open(file).use { OperitChatImporter.convert(it.conversations().single(), assistant) }
    }
    private class Sink : DeepSeekImportSink {
        val saved = mutableMapOf<Uuid, Conversation>()
        override suspend fun exists(id: Uuid) = id in saved
        override suspend fun insert(conversation: Conversation): Boolean =
            if (conversation.id in saved) false else { saved[conversation.id] = conversation; true }
    }

    @Test fun oldAndNewBodiesWorkAcrossVersionsAndHeaderOrderWithoutChangingIds() {
        val ids = mutableSetOf<Uuid>()
        for (version in listOf(1, 2, 3, 4, 99)) for (late in listOf(false, true)) {
            val rows = listOf(row(body(text = "<think>old</think>reply")),
                row(body(listOf(section("thinking", "new"), section("text", "reply")))))
            val f = file(rows, version, late)
            val preview = OperitChatArchive.inspect(f)
            assertEquals(version !in 2..3, preview.warnings.any { it.contains("文件声明版本") })
            val chat = converted(f); ids += chat.id
            assertEquals(2, chat.messageNodes.size)
            assertEquals(listOf("old", "new"), chat.currentMessages.map {
                it.parts.filterIsInstance<UIMessagePart.Reasoning>().single().reasoning
            })
        }
        assertEquals(setOf(operitImportId("conversation", "compat-chat")), ids)
    }

    @Test fun typedSectionsPreserveOrderAndLiteralThinkTextAndHistoricalToolsStayInert() {
        val tool = "<tool name=\"shell\">do not execute</tool>"
        val rows = listOf(row(body(listOf(section("thinking", "one</think>literal"),
            section("text", "<think>literal body</think>"), section("tool_call", tool, "raw"),
            section("tool_result", "<result>historical</result>", "raw"), section("thinking", "two"),
            section("status", "<status>done</status>", "raw"), section("text", "  final🙂\r\n")))))
        val message = converted(file(rows)).currentMessages.single()
        assertEquals(7, message.parts.size)
        assertEquals(listOf("one</think>literal", "two"), message.parts.filterIsInstance<UIMessagePart.Reasoning>().map { it.reasoning })
        assertEquals("<think>literal body</think>", (message.parts[1] as UIMessagePart.Text).text)
        assertEquals(tool, (message.parts[2] as UIMessagePart.Text).text)
        assertEquals("  final🙂\r\n", (message.parts.last() as UIMessagePart.Text).text)
        assertTrue(message.getTools().isEmpty()); assertNull(message.modelId)
        assertEquals(message, JsonInstant.decodeFromString<me.rerere.ai.ui.UIMessage>(JsonInstant.encodeToString(message)))
    }

    @Test fun selectedVariantSectionsAndSummaryOmissionKeepOriginalIndices() {
        val variant = buildJsonObject {
            put("variantIndex", 7); put("sections", JsonArray(listOf(section("thinking", "selected reason"), section("text", "selected reply"))))
        }
        val f = file(listOf(row(body(text = "summary", sender = "summary")),
            row(body(listOf(section("text", "not selected")), selected = 7), listOf(variant))))
        val p = OperitChatArchive.inspect(f).conversations.single()
        assertEquals(1, p.messageCount); assertEquals(1, p.omittedSummaryCount)
        val m = converted(f).currentMessages.single()
        assertEquals(operitImportId("message", "compat-chat", "1"), m.id)
        assertEquals("selected reply", (m.parts.last() as UIMessagePart.Text).text)
        assertEquals("selected reason", (m.parts.first() as UIMessagePart.Reasoning).reasoning)
    }

    @Test fun extraMetadataIsValidatedButDoesNotInstallSettingsOrFolders() {
        val extras = buildJsonObject {
            put("folders", JsonArray(listOf(buildJsonObject { put("id", "do-not-import"); put("path", "../../ignored") })))
            put("futureMetadata", buildJsonObject { put("list", JsonArray(listOf(JsonPrimitive(1), JsonNull))) })
            put("settings", buildJsonObject { put("apiKey", "SYNTHETIC_DO_NOT_COPY"); put("systemPrompt", "SYNTHETIC_DO_NOT_COPY") })
        }
        val chat = converted(file(listOf(row(body(text = "body"))), extra = extras))
        assertNull(chat.folderId); assertNull(chat.customSystemPrompt); assertNull(chat.workspaceCwd)
        assertFalse(JsonInstant.encodeToString(chat).contains("SYNTHETIC_DO_NOT_COPY"))
    }

    @Test fun unknownSectionsAndToolsWithoutRawArePreservedCompletelyWithWarning() {
        val unknown = buildJsonObject { put("type", "future_image"); put("content", "future body"); put("payload", buildJsonObject { put("x", 2) }) }
        val tool = buildJsonObject { put("type", "tool_call"); put("name", "never_run"); put("params", buildJsonObject { put("x", 1) }) }
        val f = file(listOf(row(body(listOf(unknown, tool)))))
        assertTrue(OperitChatArchive.inspect(f).warnings.any { it.contains("2 个内容段") })
        val m = converted(f).currentMessages.single()
        assertEquals(listOf(unknown, tool), m.parts.map { Json.parseToJsonElement((it as UIMessagePart.Text).text) })
        assertTrue(m.getTools().isEmpty())
    }

    @Test fun missingBodyMalformedSectionsAndConflictingRepresentationsNeverBecomeEmptyMessages() {
        val bad = listOf(body(), body(text = "wrong", sections = listOf(section("text", "actual"))),
            JsonObject(body() + ("sections" to JsonPrimitive("invalid"))),
            JsonObject(body() + ("sections" to JsonArray(listOf(JsonNull)))),
            body(listOf(buildJsonObject { put("type", "thinking") })))
        bad.forEach { b -> assertThrows(IllegalArgumentException::class.java) { OperitChatArchive.inspect(file(listOf(row(b)))) } }
        assertEquals("", (converted(file(listOf(row(body(emptyList()))))).currentMessages.single().parts.single() as UIMessagePart.Text).text)
        assertEquals("same", (converted(file(listOf(row(body(listOf(section("text", "same")), "same"))))).currentMessages.single().parts.single() as UIMessagePart.Text).text)
    }

    @Test fun unknownRoleOrMissingSelectedVariantStillRejectsFutureVersions() {
        listOf(body(text = "x", sender = "future_role"), body(text = "x", selected = 7)).forEach { b ->
            assertThrows(IllegalArgumentException::class.java) { OperitChatArchive.inspect(file(listOf(row(b)), version = 99)) }
        }
        assertThrows(IllegalArgumentException::class.java) { OperitChatArchive.inspect(file(listOf(row(body(text = "x"))), version = 0)) }
    }

    @Test fun validationOfUnselectedVariantsAndLateEnvelopeStillPreventsAnyWrites() = runBlocking {
        val invalidVariant = buildJsonObject { put("variantIndex", 1); put("sections", JsonArray(listOf(JsonNull))) }
        val f = file(listOf(row(body(text = "selected"), listOf(invalidVariant))), version = 99, lateHeader = true)
        val sink = Sink()
        try {
            OperitChatImporter(sink).import(f, assistant, mapOf("compat-chat" to "selected"), OperitChatImporter.archiveFingerprint(f))
            fail()
        } catch (_: DeepSeekImportException) { }
        assertTrue(sink.saved.isEmpty())
    }

    @Test fun additiveImportAndRepeatImportAcrossBodyVersionsKeepExistingChats() = runBlocking {
        val sink = Sink(); val importer = OperitChatImporter(sink)
        val original = file(listOf(row(body(text = "old reply"))), version = 2)
        val updated = file(listOf(row(body(listOf(section("text", "new reply"))))), version = 99)
        val selected = mapOf("compat-chat" to "selected")
        assertEquals(1, importer.import(original, assistant, selected, OperitChatImporter.archiveFingerprint(original)).imported)
        val saved = sink.saved.values.single()
        assertEquals(1, importer.import(updated, assistant, selected, OperitChatImporter.archiveFingerprint(updated)).skipped)
        assertSame(saved, sink.saved.values.single())
        assertEquals("old reply", (saved.currentMessages.single().parts.single() as UIMessagePart.Text).text)
        val fresh = Sink()
        assertEquals(1, OperitChatImporter(fresh).import(updated, assistant, selected, OperitChatImporter.archiveFingerprint(updated)).imported)
        assertEquals("new reply", (fresh.saved.values.single().currentMessages.single().parts.single() as UIMessagePart.Text).text)
    }

    @Test fun ignoredMetadataStillRejectsDuplicateKeysMalformedJsonAndExcessiveDepth() {
        val f = file(listOf(row(body(text = "ok"))))
        val good = f.readText()
        for (bad in listOf("{\"a\":1,\"a\":2}", "[}", "[".repeat(55) + "0" + "]".repeat(55))) {
            f.writeText(good.replace("\"folders\":[]", "\"folders\":$bad"))
            assertThrows(IllegalArgumentException::class.java) { OperitChatArchive.inspect(f) }
        }
    }

    @Test fun longSectionedMessageIsPartitionedLosslesslyAndReimportIsIdempotent() = runBlocking {
        val sections = listOf(section("thinking", "理".repeat(100_000)), section("text", "文".repeat(100_000)),
            section("tool_result", "<result>" + "x".repeat(300_000) + "</result>", "raw"), section("text", "末尾🙂"))
        val f = file(listOf(row(body(sections))))
        val c = converted(f)
        assertTrue(c.messageNodes.size > 1)
        c.messageNodes.forEach { me.rerere.rikkahub.data.db.MessageNodeBudget.measureNode(it.messages) }
        val parts = c.currentMessages.flatMap { it.parts }
        assertEquals(listOf("理".repeat(100_000), "文".repeat(100_000),
            "<result>" + "x".repeat(300_000) + "</result>", "末尾🙂"), parts.map {
            when (it) { is UIMessagePart.Text -> it.text; is UIMessagePart.Reasoning -> it.reasoning; else -> error("unexpected part") }
        })
        assertEquals(operitImportId("message", "compat-chat", "0"), c.currentMessages.first().id)
        assertEquals(c, converted(f))
        val sink = Sink(); val importer = OperitChatImporter(sink); val hash = OperitChatImporter.archiveFingerprint(f)
        assertEquals(1, importer.import(f, assistant, mapOf("compat-chat" to "selected"), hash).imported)
        assertEquals(c, sink.saved.values.single())
        assertEquals(1, importer.import(f, assistant, mapOf("compat-chat" to "selected"), hash).skipped)
    }

    @Test fun extraEnvelopeMetadataIsBoundedRatherThanAnUnboundedKeySet() {
        val extras = buildJsonObject { repeat(257) { put("field$it", it) } }
        assertThrows(IllegalArgumentException::class.java) {
            OperitChatArchive.inspect(file(listOf(row(body(text = "x"))), extra = extras))
        }
        assertThrows(IllegalArgumentException::class.java) {
            OperitChatArchive.inspect(file(listOf(row(body(text = "x"))), extra = buildJsonObject { put("x".repeat(257), 1) }))
        }
    }
}
