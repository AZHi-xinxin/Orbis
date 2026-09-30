package me.rerere.rikkahub.data.sync.importer

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
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
import kotlin.uuid.Uuid

/** Synthetic rollout bytes and in-memory sink only. Never reads Codex's real sessions or keys. */
class CodexChatImporterTest {
    @get:Rule val temporary = TemporaryFolder()
    private val assistant = Uuid.parse("11111111-1111-4111-8111-111111111111")
    private val session = "019dc55a-6700-7000-8000-000000000001"
    private val time = "2026-09-26T14:31:36.123Z"

    private fun record(type: String, payload: JsonObject) = buildJsonObject {
        put("timestamp", time); put("type", type); put("payload", payload)
    }.toString()

    private fun response(role: String, text: String, image: Boolean = false) = record("response_item", buildJsonObject {
        put("type", "message"); put("role", role); put("id", "source-id-$role")
        if (role == "assistant") put("channel", "final")
        put("content", JsonArray(listOf(buildJsonObject {
            put("type", if (role == "user") "input_text" else "output_text"); put("text", text)
        }) + if (image) listOf(buildJsonObject { put("type", "input_image"); put("image_url", "file:///DO_NOT_READ") }) else emptyList()))
    })

    private fun archive(body: String = "  exact\r\n正文🙂  ", extras: List<String> = emptyList(), image: Boolean = false): File {
        val meta = record("session_meta", buildJsonObject {
            put("id", session); put("timestamp", time)
            put("cwd", "DO_NOT_RETAIN_WORKSPACE")
            put("instructions", "DO_NOT_RETAIN_SYSTEM")
        })
        return temporary.newFile("synthetic-${Uuid.random()}.jsonl").also { file ->
            file.writeText((listOf(meta) + extras + listOf(response("user", body, image), response("assistant", "answer")))
                .joinToString("\n", postfix = "\n"))
        }
    }

    private fun source(file: File) = file.inputStream().use { CodexChatArchive.parse(it) }

    private class Sink : DeepSeekImportSink {
        val saved = linkedMapOf<Uuid, Conversation>()
        var insertAttempts = 0
        var refuseInsert = false
        override suspend fun exists(id: Uuid) = id in saved
        override suspend fun insert(conversation: Conversation): Boolean {
            insertAttempts++
            if (refuseInsert || conversation.id in saved) return false
            saved[conversation.id] = conversation
            return true
        }
    }

    @Test fun conversionKeepsExactTextRolesTimesAndSourceMetadata() {
        val parsed = source(archive())
        val converted = CodexChatImporter.convert(parsed, assistant)
        assertEquals(assistant, converted.assistantId)
        assertEquals(Instant.parse(time), converted.createAt)
        assertEquals(Instant.parse(time), converted.updateAt)
        assertEquals(2, converted.messageNodes.size)
        assertEquals(listOf(MessageRole.USER, MessageRole.ASSISTANT), converted.currentMessages.map { it.role })
        val text = converted.currentMessages.first().parts.single() as UIMessagePart.Text
        assertEquals("  exact\r\n正文🙂  ", text.text)
        assertEquals("codex_original_jsonl", text.metadata!!["import_source"]!!.jsonPrimitive.content)
        assertEquals(session, text.metadata!!["source_session_id"]!!.jsonPrimitive.content)
        assertEquals("2", text.metadata!!["source_line"]!!.jsonPrimitive.content)
        assertEquals(time, text.metadata!!["source_timestamp"]!!.jsonPrimitive.content)
        assertTrue(converted.messageNodes.all { it.messages.size == 1 && it.selectIndex == 0 })
        assertTrue(converted.currentMessages.all { it.finishedAt == it.createdAt })
    }

    @Test fun historyIsInertAndDoesNotCarrySystemToolsPermissionsOrWorkspaceConfiguration() {
        val extras = listOf(
            response("system", "DO_NOT_RETAIN_SYSTEM_PROMPT"),
            response("developer", "DO_NOT_RETAIN_DEVELOPER"),
            record("response_item", buildJsonObject {
                put("type", "function_call"); put("name", "dangerous_function"); put("arguments", "DO_NOT_RETAIN_EXECUTION")
            }),
            record("response_item", buildJsonObject { put("type", "function_call_output"); put("output", "DO_NOT_RETAIN_OUTPUT") }),
            record("turn_context", buildJsonObject { put("approval_policy", "never"); put("api_key", "DO_NOT_RETAIN_CONFIG") }),
        )
        val converted = CodexChatImporter.convert(source(archive(extras = extras)), assistant)
        assertNull(converted.customSystemPrompt)
        assertEquals(OrbisConversationPrompt(), converted.orbisPrompt)
        assertTrue(converted.modeInjectionIds.isEmpty())
        assertTrue(converted.lorebookIds.isEmpty())
        assertNull(converted.workspaceCwd)
        assertNull(converted.folderId)
        assertFalse(converted.isPinned)
        assertFalse(converted.newConversation)
        assertEquals(0L, converted.compactionEpoch)
        assertTrue(converted.currentMessages.all { message ->
            message.parts.all { it is UIMessagePart.Text } && message.getTools().isEmpty() &&
                message.modelId == null && message.orbisEvent == null
        })
        assertFalse(JsonInstant.encodeToString(converted).contains("DO_NOT_RETAIN"))
    }

    @Test fun omittedImageReferencesGetVisibleNoticeButNeverBecomeAttachments() {
        val converted = CodexChatImporter.convert(source(archive(image = true)), assistant)
        val parts = converted.currentMessages.first().parts
        assertEquals(2, parts.size)
        assertTrue(parts.all { it is UIMessagePart.Text })
        assertTrue((parts.last() as UIMessagePart.Text).text.contains("未读取或下载附件"))
        assertFalse(JsonInstant.encodeToString(converted).contains("DO_NOT_READ"))
    }

    @Test fun importIsAdditiveIdempotentAndNeverChangesSourceOrExistingAssistantOwnership() = runBlocking {
        val file = archive()
        val original = file.readBytes()
        val hash = CodexChatImporter.archiveFingerprint(file)
        val sink = Sink()
        val existing = Conversation(assistantId = Uuid.random(), messageNodes = emptyList(), title = "unrelated")
        sink.saved[existing.id] = existing
        val importer = CodexChatImporter(sink)
        val first = importer.import(file, assistant, session, hash)
        assertEquals(1, first.imported)
        assertEquals(2, first.messages)
        val saved = sink.saved.getValue(codexImportId("conversation", session))
        val second = importer.import(file, Uuid.random(), session, hash)
        assertEquals(1, second.skipped)
        assertEquals(0, second.imported)
        assertEquals(1, sink.insertAttempts)
        assertEquals(saved, sink.saved.getValue(saved.id))
        assertSame(existing, sink.saved[existing.id])
        assertArrayEquals(original, file.readBytes())
    }

    @Test fun deterministicIdentitiesAreNamespacedAndLengthDelimited() {
        assertEquals(codexImportId("node", session, "2"), codexImportId("node", session, "2"))
        assertNotEquals(codexImportId("node", session, "2"), codexImportId("message", session, "2"))
        assertNotEquals(codexImportId("node", "a/b", "c"), codexImportId("node", "a", "b/c"))
        assertNotEquals(codexImportId("conversation", session), deepSeekImportId("conversation", session))
    }

    @Test fun refusedAtomicInsertIsReportedAsSkippedWithoutPretendingSuccess() = runBlocking {
        val file = archive()
        val sink = Sink().also { it.refuseInsert = true }
        val result = CodexChatImporter(sink).import(file, assistant, session, CodexChatImporter.archiveFingerprint(file))
        assertEquals(1, result.skipped)
        assertEquals(0, result.imported)
        assertEquals(1, sink.insertAttempts)
        assertTrue(sink.saved.isEmpty())
    }

    @Test fun previewFingerprintTamperingRejectsBeforeAnyWrite() = runBlocking {
        val file = archive()
        val hash = CodexChatImporter.archiveFingerprint(file)
        file.appendText(record("event_msg", buildJsonObject { put("type", "user_message"); put("message", "changed after preview") }) + "\n")
        val sink = Sink()
        try {
            CodexChatImporter(sink).import(file, assistant, session, hash)
            fail("changed archive must require a new preview")
        } catch (failure: DeepSeekImportException) {
            assertEquals(0, failure.partialResult.imported)
        }
        assertEquals(0, sink.insertAttempts)
        assertTrue(sink.saved.isEmpty())
    }

    @Test fun wrongSessionSelectionRejectsBeforeAnyWriteEvenWithCorrectHash() = runBlocking {
        val file = archive()
        val sink = Sink()
        try {
            CodexChatImporter(sink).import(file, assistant, "not-the-preview-session", CodexChatImporter.archiveFingerprint(file))
            fail("wrong selection must fail")
        } catch (_: DeepSeekImportException) { }
        assertEquals(0, sink.insertAttempts)
    }

    @Test fun malformedLaterLinePreventsPartialImport() = runBlocking {
        val file = archive()
        file.appendText("{\"broken\":\n")
        val sink = Sink()
        try {
            CodexChatImporter(sink).import(file, assistant, session, CodexChatImporter.archiveFingerprint(file))
            fail("malformed source must not partially commit")
        } catch (_: DeepSeekImportException) { }
        assertEquals(0, sink.insertAttempts)
    }

    @Test fun oversizedSingleMessageIsRefusedWithoutTruncationOrPartialCommit() = runBlocking {
        val file = archive(body = "x".repeat(800_000))
        val original = file.readBytes()
        val parsed = source(file)
        assertEquals(800_000, parsed.messages.first().text.length)
        assertThrows(IllegalArgumentException::class.java) { CodexChatImporter.convert(parsed, assistant) }
        val sink = Sink()
        try {
            CodexChatImporter(sink).import(file, assistant, session, CodexChatImporter.archiveFingerprint(file))
            fail("oversized node must not be written")
        } catch (failure: DeepSeekImportException) {
            assertEquals(0, failure.partialResult.imported)
        }
        assertEquals(0, sink.insertAttempts)
        assertArrayEquals(original, file.readBytes())
    }

    @Test fun cancellationBeforeCommitDoesNotCreateAConversation() = runBlocking {
        val file = archive()
        val sink = Sink()
        try {
            CodexChatImporter(sink).import(file, assistant, session, CodexChatImporter.archiveFingerprint(file)) {
                if (it.completed == 0) throw CancellationException("synthetic cancellation")
            }
            fail("cancelled import must throw its partial result")
        } catch (cancelled: DeepSeekImportCancelledException) {
            assertEquals(0, cancelled.partialResult.imported)
        }
        assertEquals(0, sink.insertAttempts)
    }

    @Test fun cancellationAfterCommitPreservesReceiptAndRetrySkipsInsteadOfOverwriting() = runBlocking {
        val file = archive(image = true)
        val hash = CodexChatImporter.archiveFingerprint(file)
        val sink = Sink()
        val importer = CodexChatImporter(sink)
        try {
            importer.import(file, assistant, session, hash) {
                if (it.completed == 1) throw CancellationException("synthetic cancellation after commit")
            }
            fail("cancelled caller must receive the committed count")
        } catch (cancelled: DeepSeekImportCancelledException) {
            assertEquals(1, cancelled.partialResult.imported)
            assertEquals(2, cancelled.partialResult.messages)
            assertEquals(1, cancelled.partialResult.attachmentReferences)
        }
        val saved = sink.saved.values.single()
        val resumed = importer.import(file, assistant, session, hash)
        assertEquals(1, resumed.skipped)
        assertEquals(0, resumed.imported)
        assertEquals(saved, sink.saved.values.single())
        assertEquals(1, sink.insertAttempts)
    }

    @Test fun conversionCancellationAndFingerprintCancellationPropagate() {
        val file = archive()
        assertThrows(CancellationException::class.java) {
            CodexChatImporter.convert(source(file), assistant) { throw CancellationException() }
        }
        assertThrows(CancellationException::class.java) {
            CodexChatImporter.archiveFingerprint(file) { throw CancellationException() }
        }
    }

    @Test fun titleIsBoundedAndFallsBackForImageOnlyOrAssistantOnlyFiles() {
        val parsed = source(archive(body = "\n" + "标题".repeat(100)))
        assertEquals(80, CodexChatImporter.title(parsed).length)
        val imageOnly = parsed.copy(messages = listOf(parsed.messages.first().copy(text = "", omittedAttachmentCount = 1)))
        assertTrue(CodexChatImporter.title(imageOnly).startsWith("Codex "))
        val assistantOnly = parsed.copy(messages = listOf(parsed.messages.last()))
        assertTrue(CodexChatImporter.title(assistantOnly).startsWith("Codex "))
    }
}
