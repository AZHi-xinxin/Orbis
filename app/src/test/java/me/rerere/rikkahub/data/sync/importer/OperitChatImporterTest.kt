package me.rerere.rikkahub.data.sync.importer

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.OrbisConversationPrompt
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.Instant
import java.time.ZoneId
import kotlin.uuid.Uuid

/** Synthetic JSON and a memory-only transaction sink; never opens real backups or executes imported history. */
class OperitChatImporterTest {
    @get:Rule val temporary = TemporaryFolder()
    private val assistant = Uuid.parse("11111111-1111-4111-8111-111111111111")
    private val milliseconds = 1_790_755_200_123L

    private fun message(sender: String = "user", content: String = "  文字🙂\r\n第二行  ",
        time: Long = milliseconds, selected: Int = 0, variants: List<Pair<Int, String>> = emptyList()): JsonObject =
        buildJsonObject {
            put("baseMessage", buildJsonObject {
                put("sender", sender); put("content", content); put("timestamp", time)
                put("selectedVariantIndex", selected); put("variantCount", variants.size + 1)
                put("roleName", "测试显示名"); put("modelName", "fixture-model")
            })
            put("variants", JsonArray(variants.map { (index, text) -> buildJsonObject {
                put("variantIndex", index); put("content", text); put("roleName", "另一回答")
                put("modelName", "fixture-variant")
            } }))
        }

    private fun chat(id: String = "fixture-chat", messages: List<JsonObject> = listOf(message(), message("ai", "答复")),
        extra: Map<String, kotlinx.serialization.json.JsonElement> = emptyMap()): JsonObject = buildJsonObject {
        put("id", id); put("title", "测试标题 $id")
        put("createdAt", "2026-09-30T12:00:00.123"); put("updatedAt", "2026-09-30T12:01:00")
        put("messages", JsonArray(messages)); extra.forEach { (key, value) -> put(key, value) }
    }

    private fun archive(vararg chats: JsonObject): File = textFile(buildJsonObject {
        put("archiveType", "operit_chat_archive"); put("formatVersion", 2); put("exportedAt", milliseconds)
        put("chats", JsonArray(chats.toList().ifEmpty { listOf(chat()) }))
    }.toString())

    private fun textFile(text: String): File = temporary.newFile("synthetic-${Uuid.random()}.json").also { it.writeText(text) }
    private fun source(file: File, zone: ZoneId = ZoneId.of("UTC")): List<OperitConversation> =
        OperitChatArchive.open(file, timeZone = zone).use { it.conversations().toList() }
    private fun select(vararg ids: String) = ids.associateWith { OperitChatArchive.SELECTED_PATH }

    private class Sink : DeepSeekImportSink {
        val saved = linkedMapOf<Uuid, Conversation>()
        var insertAttempts = 0
        var refuse = false
        override suspend fun exists(id: Uuid) = id in saved
        override suspend fun insert(conversation: Conversation): Boolean {
            insertAttempts++
            if (refuse || conversation.id in saved) return false
            saved[conversation.id] = conversation
            return true
        }
    }

    @Test fun previewListsEveryConversationAndOnlyTheCurrentlySelectedPath() {
        val file = archive(chat("one"), chat("two", listOf(message("ai", "原始", selected = 2,
            variants = listOf(1 to "旧回答", 2 to "选中回答")))))
        val preview = OperitChatArchive.inspect(file)
        assertEquals(listOf("one", "two"), preview.conversations.map { it.sourceId })
        assertEquals(listOf(2, 1), preview.conversations.map { it.messageCount })
        assertEquals(1, preview.conversations.last().branchPointCount)
        assertTrue(preview.conversations.all { it.branches.single().leafId == "selected" })
        assertTrue(preview.conversations.all { it.defaultSelectionReason.contains("local_timezone") })
    }

    @Test fun selectedReasoningIsFoldableAndNoHistoricalToolsBecomeExecutable() {
        val raw = "<think>first</think>answer\n<tool_result_1>inert data</tool_result_1>\n<think>second</think>final"
        val file = archive(chat(messages = listOf(message("ai", "unselected", selected = 1, variants = listOf(1 to raw)))))
        val before = file.readBytes()
        val preview = OperitChatArchive.inspect(file)
        assertTrue(preview.warnings.any { it.contains("可折叠") })
        val message = OperitChatImporter.convert(source(file).single(), assistant).currentMessages.single()
        assertEquals(listOf("first", "second"), message.parts.filterIsInstance<UIMessagePart.Reasoning>().map { it.reasoning })
        assertFalse(message.toText().contains("<think>")); assertTrue(message.toText().contains("final"))
        assertTrue(message.getTools().isEmpty())
        assertTrue(message.parts.all { it.metadata?.get("source_selected_variant")?.jsonPrimitive?.content == "1" })
        assertEquals(message, JsonInstant.decodeFromString<me.rerere.ai.ui.UIMessage>(JsonInstant.encodeToString(message)))
        assertArrayEquals(before, file.readBytes())
    }

    @Test fun explicitCorrectedCopyNeverOverwritesEarlierImportAndIsIdempotent() = runBlocking {
        val file = archive(chat(messages = listOf(message("ai", "<think>reason</think>body"))))
        val hash = OperitChatImporter.archiveFingerprint(file)
        val sink = Sink()
        val oldId = operitImportId("conversation", "fixture-chat")
        val old = OperitChatImporter.convert(source(file).single().let { original ->
            original.copy(messages = original.messages.map { it.copy(text = "legacy raw original") })
        }, assistant)
        sink.saved[oldId] = old
        val importer = OperitChatImporter(sink)
        assertEquals(1, importer.import(file, assistant, select("fixture-chat"), hash).skipped)
        val copyChoice = mapOf("fixture-chat" to OperitChatArchive.CORRECTED_COPY_PATH)
        assertEquals(1, importer.import(file, assistant, copyChoice, hash).imported)
        assertEquals(old, sink.saved[oldId]); assertEquals(2, sink.saved.size)
        val copy = sink.saved.values.single { it.id != oldId }
        assertTrue(copy.title.endsWith("（整理副本）"))
        assertTrue(copy.currentMessages.single().parts.any { it is UIMessagePart.Reasoning })
        assertNotEquals(old.currentMessages.single().id, copy.currentMessages.single().id)
        assertEquals(1, importer.import(file, assistant, copyChoice, hash).skipped)
    }

    @Test fun selectedRegenerationNotBaseAnswerIsImportedWithoutCreatingExecutableBranches() {
        val parsed = source(archive(chat(messages = listOf(message("ai", "原始", selected = 2,
            variants = listOf(1 to "旧回答", 2 to "选中回答")))))).single()
        val converted = OperitChatImporter.convert(parsed, assistant)
        val text = converted.currentMessages.single().parts.single() as UIMessagePart.Text
        assertEquals("选中回答", text.text)
        assertEquals("2", text.metadata!!["source_selected_variant"]!!.jsonPrimitive.content)
        assertEquals("3", text.metadata!!["source_variant_count"]!!.jsonPrimitive.content)
        assertEquals("fixture-variant", text.metadata!!["source_model"]!!.jsonPrimitive.content)
        assertEquals(1, converted.messageNodes.single().messages.size)
        assertEquals(0, converted.messageNodes.single().selectIndex)
    }

    @Test fun originalAnswerAndArrayOrderAndMillisecondsArePreservedEvenWhenTimestampsGoBackwards() {
        val parsed = source(archive(chat(messages = listOf(message(time = milliseconds + 100),
            message("ai", "答复", time = milliseconds))))).single()
        val converted = OperitChatImporter.convert(parsed, assistant)
        assertEquals(listOf(MessageRole.USER, MessageRole.ASSISTANT), converted.currentMessages.map { it.role })
        val text = converted.currentMessages.first().parts.single() as UIMessagePart.Text
        assertEquals("  文字🙂\r\n第二行  ", text.text)
        assertEquals((milliseconds + 100).toString(), text.metadata!!["source_timestamp_ms"]!!.jsonPrimitive.content)
        assertEquals("operit_json_v2", text.metadata!!["import_source"]!!.jsonPrimitive.content)
        assertTrue(converted.currentMessages.all { it.finishedAt == it.createdAt })
    }

    @Test fun localDatesUseExplicitImportTimezoneAndKeepOriginalUnzonedValues() {
        val parsed = source(archive(), ZoneId.of("Asia/Shanghai")).single()
        assertEquals(Instant.parse("2026-09-30T04:00:00.123Z"), parsed.createdAt)
        val converted = OperitChatImporter.convert(parsed, assistant)
        val metadata = (converted.currentMessages.first().parts.single() as UIMessagePart.Text).metadata!!
        assertEquals("2026-09-30T12:00:00.123", metadata["source_created_at_local"]!!.jsonPrimitive.content)
        assertEquals("Asia/Shanghai", metadata["source_timezone_assumption"]!!.jsonPrimitive.content)
    }

    @Test fun headerMayFollowChatsAndUtf8BomIsAccepted() {
        val file = textFile("\uFEFF" + buildJsonObject {
            put("chats", JsonArray(listOf(chat()))); put("formatVersion", 2); put("archiveType", "operit_chat_archive")
        })
        assertEquals(1, OperitChatArchive.inspect(file).conversations.size)
    }

    @Test fun settingsPromptsWorkspaceAndIdentityConfigurationAreNotImported() {
        val file = archive(chat(extra = mapOf("workspace" to JsonPrimitive("DO_NOT_COPY_WORKSPACE"),
            "workspaceEnv" to JsonPrimitive("DO_NOT_COPY_ENV"), "characterCardName" to JsonPrimitive("DO_NOT_COPY_CHARACTER"),
            "parentChatId" to JsonPrimitive("DO_NOT_COPY_PARENT"), "pinned" to JsonPrimitive(true),
            "apiKey" to JsonPrimitive("DO_NOT_COPY_KEY"))))
        val converted = OperitChatImporter.convert(source(file).single(), assistant)
        assertEquals(assistant, converted.assistantId)
        assertNull(converted.customSystemPrompt); assertNull(converted.workspaceCwd); assertNull(converted.folderId)
        assertEquals(OrbisConversationPrompt(), converted.orbisPrompt)
        assertTrue(converted.modeInjectionIds.isEmpty()); assertTrue(converted.lorebookIds.isEmpty())
        assertFalse(converted.isPinned); assertFalse(converted.newConversation)
        assertFalse(JsonInstant.encodeToString(converted).contains("DO_NOT_COPY"))
    }

    @Test fun embeddedHistoricalToolsAndAttachmentsRemainTextAndHaveNoPermissionOrMediaObjects() {
        val body = "<tool name=\"shell\">echo no</tool>\n<attachment id=\"file:///never-read\" filename=\"a.png\" />\n![img](https://invalid.test/never-fetch)"
        val converted = OperitChatImporter.convert(source(archive(chat(messages = listOf(message("ai", body))))).single(), assistant)
        val message = converted.currentMessages.single()
        assertTrue(message.parts.all { it is UIMessagePart.Text })
        assertEquals(body, (message.parts.first() as UIMessagePart.Text).text)
        assertTrue((message.parts.last() as UIMessagePart.Text).text.contains("未读取或下载附件"))
        assertTrue(message.getTools().isEmpty()); assertNull(message.orbisEvent); assertNull(message.modelId)
    }

    @Test fun unsupportedRolesAreNotPromotedToHumanOrAssistant() {
        listOf("system", "tool", "assistant", "unknown", "Summary", " summary").forEach { sender ->
            assertThrows(IllegalArgumentException::class.java) { OperitChatArchive.inspect(archive(chat(messages = listOf(message(sender))))) }
        }
    }

    @Test fun summariesAreCountedButNeverBecomeMessagesPromptsOrTools() {
        val sentinel = "SUMMARY_MUST_NOT_BE_IMPORTED <tool name=\"shell\">not a call</tool>"
        val file = archive(chat(messages = listOf(message(), message("summary", sentinel),
            message("ai", "base", selected = 2, variants = listOf(1 to "not selected", 2 to "selected")),
            message("summary", sentinel), message("ai", ""))))
        val preview = OperitChatArchive.inspect(file)
        val row = preview.conversations.single()
        assertEquals(5, row.totalNodes); assertEquals(3, row.messageCount)
        assertEquals(2, row.omittedSummaryCount); assertEquals(1, row.branchPointCount)
        assertEquals(3, row.branches.single().messageCount)
        assertTrue(preview.warnings.single().contains("跳过 2 条"))
        assertFalse(preview.warnings.joinToString().contains(sentinel))

        val parsed = source(file).single()
        assertEquals(2, parsed.omittedSummaryCount)
        assertEquals(listOf(0, 2, 4), parsed.messages.map { it.sourceIndex })
        val converted = OperitChatImporter.convert(parsed, assistant)
        assertEquals(listOf(MessageRole.USER, MessageRole.ASSISTANT, MessageRole.ASSISTANT),
            converted.currentMessages.map { it.role })
        assertEquals(listOf("  文字🙂\r\n第二行  ", "selected", ""),
            converted.currentMessages.map { (it.parts.single() as UIMessagePart.Text).text })
        converted.messageNodes.forEachIndexed { position, node ->
            val sourceIndex = listOf(0, 2, 4)[position]
            assertEquals(operitImportId("node", "fixture-chat", sourceIndex.toString()), node.id)
            assertEquals(operitImportId("message", "fixture-chat", sourceIndex.toString()), node.messages.single().id)
            assertEquals(sourceIndex.toString(), (node.messages.single().parts.single() as UIMessagePart.Text)
                .metadata!!["source_message_index"]!!.jsonPrimitive.content)
            assertTrue(node.messages.single().getTools().isEmpty())
        }
        assertNull(converted.customSystemPrompt)
        assertFalse(JsonInstant.encodeToString(converted).contains("SUMMARY_MUST_NOT_BE_IMPORTED"))
    }

    @Test fun omittedSummaryPayloadIsNotInterpretedAsOrdinaryVariantOrTimestampData() {
        val summary = buildJsonObject {
            put("baseMessage", buildJsonObject { put("sender", "summary"); put("content", "internal only") })
            put("variants", JsonPrimitive("internal record has no chat variant contract"))
        }
        val parsed = source(archive(chat(messages = listOf(summary, message("ai", ""))))).single()
        assertEquals(1, parsed.omittedSummaryCount)
        assertEquals(1, parsed.messages.single().sourceIndex)
        assertEquals("", parsed.messages.single().text)
    }

    @Test fun summariesDoNotHideMalformedOrdinaryMessagesOrUnknownRoles() = runBlocking {
        listOf(message("system", "not permitted"), message("ai", selected = 2),
            message("user", time = -1)).forEach { invalid ->
            val file = archive(chat("valid"), chat("invalid", listOf(message("summary"), invalid)))
            val sink = Sink()
            try {
                OperitChatImporter(sink).import(file, assistant, select("valid"), OperitChatImporter.archiveFingerprint(file))
                fail("Malformed source must fail full validation before any commit")
            } catch (failure: DeepSeekImportException) {
                assertEquals(0, failure.partialResult.imported)
                assertEquals(0, failure.partialResult.skippedSummaries)
            }
            assertEquals(0, sink.insertAttempts)
        }
    }

    @Test fun summaryOnlyConversationsAreExcludedWhileGenuineEmptyChatsRemainSelectable() {
        val file = archive(chat("summary-only", listOf(message("summary"))), chat("normal"), chat("empty", emptyList()))
        val preview = OperitChatArchive.inspect(file)
        assertEquals(listOf("normal", "empty"), preview.conversations.map { it.sourceId })
        assertTrue(preview.warnings.any { it.contains("跳过 1 条") })
        assertTrue(preview.warnings.any { it.contains("排除 1 个") })
        val failure = assertThrows(IllegalArgumentException::class.java) {
            OperitChatArchive.inspect(archive(chat(messages = listOf(message("summary", "PRIVATE_SUMMARY_SENTINEL")))))
        }
        assertTrue(failure.message!!.contains("没有可导入的普通聊天"))
        assertFalse(failure.message!!.contains("PRIVATE_SUMMARY_SENTINEL"))
    }

    @Test fun manuallySelectingSummaryOnlyConversationCannotInsertAnEmptyReplacement() = runBlocking {
        val file = archive(chat("summary-only", listOf(message("summary"))), chat("normal"))
        val sink = Sink()
        try {
            OperitChatImporter(sink).import(file, assistant, select("summary-only", "normal"),
                OperitChatImporter.archiveFingerprint(file))
            fail("Summary-only selection must fail before committing normal selections too")
        } catch (failure: DeepSeekImportException) { assertEquals(0, failure.partialResult.imported) }
        assertEquals(0, sink.insertAttempts)
    }

    @Test fun summaryResultsOnlyCountSuccessfullyCommittedChatsAndPreserveAttachmentsAndSourceBytes() = runBlocking {
        val file = archive(chat("one", listOf(message("summary", "not copied"), message("ai", ""))),
            chat("two", listOf(message("summary"), message("user", "<attachment id=\"missing\" />"))))
        val original = file.readBytes()
        val fingerprint = OperitChatImporter.archiveFingerprint(file)
        val sink = Sink()
        val importer = OperitChatImporter(sink)
        val first = importer.import(file, assistant, select("one"), fingerprint)
        assertEquals(1, first.imported); assertEquals(1, first.messages); assertEquals(1, first.skippedSummaries)
        val second = importer.import(file, assistant, select("one", "two"), fingerprint)
        assertEquals(1, second.skipped); assertEquals(1, second.imported)
        assertEquals(1, second.skippedSummaries); assertEquals(1, second.attachmentReferences)
        val attachmentMessage = sink.saved.getValue(operitImportId("conversation", "two")).currentMessages.single()
        assertTrue(attachmentMessage.parts.all { it is UIMessagePart.Text })
        assertTrue((attachmentMessage.parts.last() as UIMessagePart.Text).text.contains("未读取或下载附件"))
        assertTrue(attachmentMessage.getTools().isEmpty())
        assertArrayEquals(original, file.readBytes())
    }

    @Test fun refusedAndOversizedImportsDoNotClaimTheirSummariesWereSkipped() = runBlocking {
        val sink = Sink().apply { refuse = true }
        val refused = archive(chat(messages = listOf(message("summary"), message("ai", "ok"))))
        val result = OperitChatImporter(sink).import(refused, assistant, select("fixture-chat"),
            OperitChatImporter.archiveFingerprint(refused))
        assertEquals(1, result.skipped); assertEquals(0, result.skippedSummaries)
        val tooLarge = archive(chat(messages = listOf(message("summary"), message("ai", "界".repeat(400_000)))))
        val failed = OperitChatImporter(Sink()).import(tooLarge, assistant, select("fixture-chat"),
            OperitChatImporter.archiveFingerprint(tooLarge))
        assertEquals(1, failed.failed); assertEquals(0, failed.skippedSummaries)
    }

    @Test fun cancellationReportsSummariesOnlyForTheDurablyCommittedSubset() = runBlocking {
        val file = archive(chat("one", listOf(message("summary"), message("ai", "one"))),
            chat("two", listOf(message("summary"), message("summary"), message("ai", "two"))))
        val sink = Sink()
        try {
            OperitChatImporter(sink).import(file, assistant, select("one", "two"),
                OperitChatImporter.archiveFingerprint(file)) { if (it.completed == 1) throw CancellationException() }
            fail("Cancellation must propagate with the committed subset")
        } catch (cancelled: DeepSeekImportCancelledException) {
            assertEquals(1, cancelled.partialResult.imported)
            assertEquals(1, cancelled.partialResult.skippedSummaries)
        }
        assertEquals(1, sink.saved.size)
    }

    @Test fun missingSelectedVariantAndDuplicateOrNonpositiveIndicesAreRejected() {
        val examples = listOf(message("ai", selected = 2),
            message("ai", variants = listOf(1 to "a", 1 to "b")),
            message("ai", variants = listOf(0 to "a")), message("user", selected = 1),
            message("user", variants = listOf(1 to "a")))
        examples.forEach { message -> assertThrows(IllegalArgumentException::class.java) {
            OperitChatArchive.inspect(archive(chat(messages = listOf(message))))
        } }
    }

    @Test fun invalidOrOutOfRangeTimestampsAndDuplicateChatIdsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { OperitChatArchive.inspect(archive(chat("same"), chat("same"))) }
        listOf(-1L, Long.MAX_VALUE).forEach { time -> assertThrows(IllegalArgumentException::class.java) {
            OperitChatArchive.inspect(archive(chat(messages = listOf(message(time = time)))))
        } }
        assertThrows(IllegalArgumentException::class.java) {
            OperitChatArchive.inspect(archive(JsonObject(chat() + ("createdAt" to JsonPrimitive("2026-09-30T12:00:00Z")))))
        }
    }

    @Test fun unsupportedArchivesDuplicateKeysMalformedJsonAndInvalidUtf8AreRejectedSafely() {
        val inputs = listOf("[]", "{\"memories\":[],\"links\":[]}",
            "{\"archiveType\":\"operit_chat_archive\",\"formatVersion\":3,\"chats\":[]}",
            "{\"archiveType\":\"operit_chat_archive\",\"formatVersion\":2,\"formatVersion\":2,\"chats\":[]}",
            "{\"archiveType\":\"operit_chat_archive\",\"formatVersion\":2,\"chats\":[],\"settings\":{}}",
            "{\"archiveType\":\"operit_chat_archive\",\"formatVersion\":2,\"chats\":[}")
        inputs.forEach { input ->
            val failure = assertThrows(IllegalArgumentException::class.java) { OperitChatArchive.inspect(textFile(input)) }
            assertFalse(failure.message!!.contains(input))
        }
        val invalid = temporary.newFile("invalid-utf8.json").also { it.writeBytes(byteArrayOf(0xc3.toByte(), 0x28)) }
        assertThrows(IllegalArgumentException::class.java) { OperitChatArchive.inspect(invalid) }
    }

    @Test fun emptyChatsArePreservedButEmptyArchiveIsRejected() {
        assertEquals(0, OperitChatArchive.inspect(archive(chat(messages = emptyList()))).conversations.single().messageCount)
        assertThrows(IllegalArgumentException::class.java) {
            OperitChatArchive.inspect(textFile("{\"archiveType\":\"operit_chat_archive\",\"formatVersion\":2,\"chats\":[]}"))
        }
    }

    @Test fun importSelectsOnlyExplicitChatsAndIsAdditiveIdempotentAcrossAssistants() = runBlocking {
        val file = archive(chat("one"), chat("two"))
        val original = file.readBytes()
        val sink = Sink()
        val existing = Conversation(assistantId = Uuid.random(), title = "unrelated", messageNodes = emptyList())
        sink.saved[existing.id] = existing
        val importer = OperitChatImporter(sink)
        val fingerprint = OperitChatImporter.archiveFingerprint(file)
        val first = importer.import(file, assistant, select("two"), fingerprint)
        assertEquals(1, first.imported); assertEquals(2, first.messages)
        val saved = sink.saved.getValue(operitImportId("conversation", "two"))
        assertEquals(assistant, saved.assistantId)
        val second = importer.import(file, Uuid.random(), select("two"), fingerprint)
        assertEquals(1, second.skipped); assertEquals(0, second.imported); assertEquals(1, sink.insertAttempts)
        assertSame(saved, sink.saved[saved.id]); assertSame(existing, sink.saved[existing.id])
        assertFalse(sink.saved.containsKey(operitImportId("conversation", "one")))
        assertArrayEquals(original, file.readBytes())
    }

    @Test fun changedFingerprintOrUnknownSelectionPreventsAllWrites() = runBlocking {
        val file = archive()
        val hash = OperitChatImporter.archiveFingerprint(file)
        file.appendText(" ")
        val sink = Sink()
        try { OperitChatImporter(sink).import(file, assistant, select("fixture-chat"), hash); fail() }
        catch (failure: DeepSeekImportException) { assertEquals(0, failure.partialResult.imported) }
        try { OperitChatImporter(sink).import(file, assistant, select("unknown"), OperitChatImporter.archiveFingerprint(file)); fail() }
        catch (_: DeepSeekImportException) { }
        assertEquals(0, sink.insertAttempts)
    }

    @Test fun malformedLaterChatAndLateInvalidHeaderPreventEarlierSelectedChatFromBeingCommitted() = runBlocking {
        val files = listOf(archive(chat("one"), chat("two", listOf(message("tool")))), textFile(buildJsonObject {
            put("chats", JsonArray(listOf(chat("one")))); put("archiveType", "wrong"); put("formatVersion", 2)
        }.toString()))
        files.forEach { file ->
            val sink = Sink()
            try { OperitChatImporter(sink).import(file, assistant, select("one"), OperitChatImporter.archiveFingerprint(file)); fail() }
            catch (_: DeepSeekImportException) { }
            assertEquals(0, sink.insertAttempts)
        }
    }

    @Test fun originalFileMutationAfterPreviewCannotAlterFrozenImport() = runBlocking {
        val file = archive()
        val sink = Sink()
        val result = OperitChatImporter(sink).import(file, assistant, select("fixture-chat"), OperitChatImporter.archiveFingerprint(file)) {
            if (it.completed == 0) file.writeText("changed while import is running")
        }
        assertEquals(1, result.imported)
        assertEquals("  文字🙂\r\n第二行  ", (sink.saved.values.single().currentMessages.first().parts.single() as UIMessagePart.Text).text)
    }

    @Test fun oversizedNodeIsReportedWithoutTruncationAndOtherSelectedChatsCanProceed() = runBlocking {
        val file = archive(chat("huge", listOf(message(content = "x".repeat(800_000)))), chat("small"))
        val original = file.readBytes()
        val sink = Sink()
        val result = OperitChatImporter(sink).import(file, assistant, select("huge", "small"), OperitChatImporter.archiveFingerprint(file))
        assertEquals(1, result.imported); assertEquals(1, result.failed)
        assertEquals("huge", result.failures.single().sourceId)
        assertFalse(sink.saved.containsKey(operitImportId("conversation", "huge")))
        assertArrayEquals(original, file.readBytes())
    }

    @Test fun cancellationBeforeCommitIsEmptyAndCancellationAfterCommitKeepsAccurateReceipt() = runBlocking {
        val file = archive(chat("one"), chat("two"))
        val hash = OperitChatImporter.archiveFingerprint(file)
        val sink = Sink()
        try { OperitChatImporter(sink).import(file, assistant, select("one", "two"), hash) {
            if (it.completed == 0) throw CancellationException()
        }; fail() } catch (cancelled: DeepSeekImportCancelledException) { assertEquals(0, cancelled.partialResult.imported) }
        assertTrue(sink.saved.isEmpty())
        try { OperitChatImporter(sink).import(file, assistant, select("one", "two"), hash) {
            if (it.completed == 1) throw CancellationException()
        }; fail() } catch (cancelled: DeepSeekImportCancelledException) { assertEquals(1, cancelled.partialResult.imported) }
        assertEquals(1, sink.saved.size)
        val resumed = OperitChatImporter(sink).import(file, assistant, select("one", "two"), hash)
        assertEquals(1, resumed.skipped); assertEquals(1, resumed.imported)
    }

    @Test fun refusedInsertCountsAsSkippedAndNeverPretendsSuccess() = runBlocking {
        val file = archive()
        val sink = Sink().also { it.refuse = true }
        val result = OperitChatImporter(sink).import(file, assistant, select("fixture-chat"), OperitChatImporter.archiveFingerprint(file))
        assertEquals(0, result.imported); assertEquals(1, result.skipped); assertTrue(sink.saved.isEmpty())
    }

    @Test fun idsAreNamespacedAndLengthDelimited() {
        assertEquals(operitImportId("node", "one", "1"), operitImportId("node", "one", "1"))
        assertNotEquals(operitImportId("node", "one", "1"), operitImportId("message", "one", "1"))
        assertNotEquals(operitImportId("node", "a/b", "c"), operitImportId("node", "a", "b/c"))
        assertNotEquals(operitImportId("conversation", "one"), deepSeekImportId("conversation", "one"))
    }

    @Test fun cancellationFromParserFingerprintAndConverterPropagates() {
        val file = archive()
        assertThrows(CancellationException::class.java) { OperitChatArchive.inspect(file) { throw CancellationException() } }
        assertThrows(CancellationException::class.java) { OperitChatImporter.archiveFingerprint(file) { throw CancellationException() } }
        assertThrows(CancellationException::class.java) { OperitChatImporter.convert(source(file).single(), assistant,
            checkCancelled = { throw CancellationException() }) }
    }
}
