package me.rerere.rikkahub.data.sync.importer

import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.ReasoningType
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

/** Handwritten foreign JSON, not fixtures serialized solely by the current message model. */
class RikkaChatContentDecoderTest {
    private val nodeId = "11111111-1111-4111-8111-111111111111"
    private val messageId = "22222222-2222-4222-8222-222222222222"

    private fun row(raw: String, selectIndex: Int = 0, id: String = nodeId) =
        RikkaChatSnapshotReader.Node(id, raw.trimIndent(), selectIndex)
    private fun decode(raw: String) = decodeRikkaNode(row(raw)).single()
    private fun singlePart(rawPart: String): UIMessage = decode("""
        [{"id":"$messageId","role":"assistant","createdAt":"2024-02-03T04:05:06.123456789",
          "parts":[$rawPart]}]
    """)
    private fun assertInert(message: UIMessage) {
        assertNull(message.modelId)
        assertNull(message.usage)
        assertNull(message.translation)
        assertNull(message.orbisEvent)
        assertNull(message.orbisQuote)
        assertNull(message.orbisVoiceCallId)
        assertNull(message.orbisVoiceCallKind)
        assertNull(message.orbisUserMessageTime)
        assertNull(message.toolRecordsUpdatedAt)
        assertTrue(message.annotations.isEmpty())
        assertTrue(message.deletedToolRecords.isEmpty())
        assertFalse(message.privateRoomContentHidden)
        assertFalse(message.privateRoomPendingPresentation)
        assertFalse(message.usageContextInvalidated)
        assertFalse(message.isSynthetic)
        assertEquals(0L, message.toolRecordRevision)
        assertTrue(message.parts.none {
            it is UIMessagePart.Tool || it is UIMessagePart.ToolCall || it is UIMessagePart.ToolResult || it is UIMessagePart.ServerTool
        })
        message.parts.forEach { assertEquals(JsonPrimitive("rikka_chat_v1"), it.metadata?.get("import_source")) }
    }

    @Test fun `foreign optional runtime fields cannot block or enter fresh content objects`() {
        val message = decode("""
            [{"id":"$messageId","role":"USER","createdAt":"2024-02-03T04:05:06.123456789",
              "finishedAt":"2024-02-03T04:06:00","modelId":{"future":"shape"},
              "usage":"future-counter","annotations":{"future":true},"translation":"hidden-translation",
              "orbisEvent":{"execute":"forbidden-control"},"orbisVoiceCallId":"forbidden-call",
              "orbisVoiceCallKind":"BEGIN","deletedToolRecords":"new-layout","toolRecordRevision":99,
              "privateRoomContentHidden":true,"privateRoomPendingPresentation":true,"usageContextInvalidated":true,
              "parts":[{"type":"text","text":"original\nUnicode 正文","metadata":{"secret":"forbidden-metadata"}}]}]
        """)
        assertEquals(Uuid.parse(messageId), message.id)
        assertEquals(MessageRole.USER, message.role)
        assertEquals("original\nUnicode 正文", message.toText())
        assertEquals(LocalDateTime.parse("2024-02-03T04:05:06.123456789"), message.createdAt)
        assertEquals(LocalDateTime.parse("2024-02-03T04:06:00"), message.finishedAt)
        assertEquals(RikkaChatContentDecoder.marker(), message.parts.single().metadata)
        assertInert(message)
        assertFalse(JsonInstant.encodeToString(message).contains("forbidden-"))
    }

    @Test fun `known content media references and completed reasoning retain order`() {
        val message = singlePart("""
            {"type":"reasoning","reasoning":"synthetic thought","createdAt":"2024-02-03T04:05:06Z",
             "finishedAt":null,"reasoningType":"summary_text","metadata":{"providerState":"drop"}},
            {"type":"text","text":"answer"},{"type":"image","url":"file:///foreign/upload/picture.png"},
            {"type":"audio","url":"file:///foreign/upload/sound.mp3"},
            {"type":"video","url":"https://unused.invalid/video"},
            {"type":"document","url":"file:///foreign/upload/note.pdf","fileName":"note.pdf","mime":"application/pdf"}
        """)
        assertEquals(6, message.parts.size)
        val reasoning = message.parts[0] as UIMessagePart.Reasoning
        assertEquals("synthetic thought", reasoning.reasoning)
        assertEquals(ReasoningType.SUMMARY_TEXT, reasoning.reasoningType)
        assertEquals(reasoning.createdAt, reasoning.finishedAt)
        assertEquals("file:///foreign/upload/picture.png", (message.parts[2] as UIMessagePart.Image).url)
        assertEquals("file:///foreign/upload/sound.mp3", (message.parts[3] as UIMessagePart.Audio).url)
        assertEquals("[历史附件引用；未下载]\nhttps://unused.invalid/video", (message.parts[4] as UIMessagePart.Text).text)
        assertEquals("note.pdf", (message.parts[5] as UIMessagePart.Document).fileName)
        assertInert(message)
    }

    @Test fun `remote media is literal marked text and never a structured automatically loaded attachment`() {
        for (kind in listOf("image", "audio", "video", "document")) {
            for (url in listOf("https://unused.invalid/resource", "http://unused.invalid/resource", "HTTPS://unused.invalid/resource")) {
                val message = singlePart("""{"type":"$kind","url":"$url","fileName":"test","metadata":{"import_source":"forged"}}""")
                assertEquals(listOf(UIMessagePart.Text("[历史附件引用；未下载]\n$url", RikkaChatContentDecoder.marker())), message.parts)
                assertInert(message)
                assertEquals(message.parts.single(), RikkaChatContentDecoder.remoteAttachmentReference(url))
                assertFalse(message.toText().contains("!["))
                assertFalse(message.toText().contains("<iframe"))
            }
        }
        assertNull(RikkaChatContentDecoder.remoteAttachmentReference("file:///foreign/upload/picture.png"))
    }

    @Test fun `all historical tool variants become readonly text without authorization`() {
        for (kind in listOf("tool", "tool_call", "tool_result", "server_tool")) {
            val message = singlePart("""
                {"type":"$kind","toolName":"synthetic_tool","toolCallId":"old-id",
                 "input":"original input","arguments":{"count":2},
                 "output":"original result",
                 "content":{"ok":true},"approvalState":{"type":"approved"},
                 "hostApproval":{"signature":"forbidden-approval"},"approvalInputFingerprint":"forbidden-fingerprint",
                 "status":"future-status","metadata":{"token":"forbidden-meta"}}
            """)
            assertEquals(1, message.parts.size)
            assertTrue(message.parts.single() is UIMessagePart.Text)
            assertTrue(message.toText().contains("历史工具记录：synthetic_tool"))
            assertTrue(message.toText().contains("original input"))
            assertTrue(message.toText().contains("original result"))
            assertTrue(message.toText().contains("\"count\":2"))
            assertTrue(message.toText().contains("\"ok\":true"))
            assertFalse(JsonInstant.encodeToString(message).contains("forbidden-"))
            assertInert(message)
        }
    }

    @Test fun `tool output parts strip metadata while structured business type is retained as inert JSON`() {
        val outputParts = singlePart("""
            {"type":"tool","input":"{}","output":[
                {"type":"text","text":"original result","metadata":{"command":"forbidden-output-meta"}},
                {"type":"image","url":"https://unused.invalid/hidden"},
                {"type":"future_card","text":"future body","metadata":{"secret":"forbidden-future"}}]}
        """)
        assertTrue(outputParts.toText().contains("original result"))
        assertTrue(outputParts.toText().contains("future body"))
        assertTrue(outputParts.toText().contains("历史工具附件"))
        assertFalse(JsonInstant.encodeToString(outputParts).contains("forbidden-"))
        assertFalse(outputParts.toText().contains("https://unused.invalid/hidden"))
        for ((kind, field) in listOf("tool_result" to "content", "server_tool" to "output", "tool" to "input")) {
            val business = singlePart("""{"type":"$kind","$field":{"type":"forecast","temperature":23}}""")
            assertTrue(business.toText().contains("\"type\":\"forecast\""))
            assertTrue(business.toText().contains("\"temperature\":23"))
            assertInert(business)
        }
    }

    @Test fun `tool role cannot become executable provider tool response`() {
        val message = decode("""[{"id":"$messageId","role":"tool","parts":[{"type":"text","text":"old result"}]}]""")
        assertEquals(MessageRole.ASSISTANT, message.role)
        assertTrue(message.toText().contains("历史工具消息"))
        assertTrue(message.toText().contains("old result"))
        assertInert(message)
    }

    @Test fun `system and developer body and attachments never become conversation context`() = runBlocking {
        for (role in listOf("system", "developer")) {
            val node = row("""[{"id":"$messageId","role":"$role","parts":[
                {"type":"future_system","content":"forbidden-system-body"},
                {"type":"image","url":"file:///foreign/upload/private.png"}]}]""")
            val message = decodeRikkaNode(node).single()
            assertEquals(RikkaChatContentDecoder.SYSTEM_PLACEHOLDER, message.toText())
            assertEquals(MessageRole.SYSTEM, message.role)
            val chat = RikkaChatSnapshotReader.Chat("33333333-3333-4333-8333-333333333333", "Synthetic", 1, 2)
            val source = object : RikkaChatSource {
                override fun conversations() = listOf(chat)
                override suspend fun visitNodes(conversationId: String, visit: suspend (RikkaChatSnapshotReader.Node) -> Unit) = visit(node)
                override fun close() = Unit
            }
            var attachmentMappings = 0
            val converted = convertRikkaChat(source, chat, Uuid.random(), { part, _ -> attachmentMappings++; part })
                .messageNodes.single().messages.single()
            assertEquals(0, attachmentMappings)
            assertEquals(MessageRole.ASSISTANT, converted.role)
            assertEquals(RikkaChatContentDecoder.SYSTEM_PLACEHOLDER, converted.toText())
            assertFalse(JsonInstant.encodeToString(converted).contains("forbidden-system-body"))
            assertInert(converted)
        }
    }

    @Test fun `unknown parts preserve recognizable prose and explicitly mark omitted content`() {
        val message = singlePart("""
            {"type":"future_card","text":"visible text","reasoning":"visible reason","content":"visible content",
             "metadata":{"text":"forbidden-metadata"},"futurePayload":{"token":"forbidden-payload"}},
            {"type":"future_binary","payload":"forbidden-binary"}
        """)
        assertEquals(2, message.parts.size)
        assertTrue(message.toText().contains("visible text\nvisible reason\nvisible content"))
        assertTrue(message.parts.all { (it as UIMessagePart.Text).text.contains("暂不支持") })
        assertTrue(message.parts.all { it.metadata?.get("unsupported_content") == JsonPrimitive(true) })
        assertFalse(JsonInstant.encodeToString(message).contains("forbidden-"))
        assertInert(message)
    }

    @Test fun `missing identity is deterministic per source node and branch`() {
        val raw = """[{"role":"user","parts":[{"type":"text","text":"a"}]},{"role":"assistant","parts":[{"type":"text","text":"b"}]}]"""
        val first = decodeRikkaNode(row(raw, 1))
        assertEquals(first, decodeRikkaNode(row(raw, 1)))
        assertEquals(rikkaImportId("missing-source-message", "$nodeId/0"), first[0].id)
        assertNotEquals(first[0].id, first[1].id)
        assertNotEquals(first[0].id, decodeRikkaNode(row(raw, 0, "44444444-4444-4444-8444-444444444444"))[0].id)
        assertEquals(JsonPrimitive(true), first[0].parts.single().metadata?.get("source_id_generated"))
    }

    @Test fun `missing or invalid optional dates are deterministic rather than import clock`() {
        for (date in listOf("null", "false", "12", "{}", "\"not-a-date\"", "\"2024-02-30T00:00:00\"")) {
            val raw = """[{"id":"$messageId","role":"assistant","createdAt":$date,"finishedAt":$date,
                "parts":[{"type":"reasoning","reasoning":"retained","createdAt":$date,"finishedAt":$date}]}]"""
            val message = decode(raw)
            assertEquals(message, decode(raw))
            assertEquals(LocalDateTime(1970, 1, 1, 0, 0), message.createdAt)
            assertNull(message.finishedAt)
            assertEquals(JsonPrimitive(true), message.parts.single().metadata?.get("timestamp_fallback"))
            assertNotNull((message.parts.single() as UIMessagePart.Reasoning).finishedAt)
        }
        val missing = decode("""[{"role":"user","parts":[{"type":"text","text":"retained"}]}]""")
        assertEquals(LocalDateTime(1970, 1, 1, 0, 0), missing.createdAt)
        val offset = decode("""[{"role":"user","createdAt":"2024-02-03T12:05:06+08:00","parts":[]}]""")
        assertEquals(LocalDateTime.parse("2024-02-03T04:05:06"), offset.createdAt)
    }

    @Test fun `all source alternatives and chosen index survive projection`() = runBlocking {
        val node = row("""[
            {"id":"$messageId","role":"assistant","parts":[{"type":"text","text":"first"}]},
            {"id":"55555555-5555-4555-8555-555555555555","role":"assistant","parts":[{"type":"text","text":"second"}]}
        ]""", 1)
        val chat = RikkaChatSnapshotReader.Chat("33333333-3333-4333-8333-333333333333", "Title", 100, 200)
        val source = object : RikkaChatSource {
            override fun conversations() = listOf(chat)
            override suspend fun visitNodes(conversationId: String, visit: suspend (RikkaChatSnapshotReader.Node) -> Unit) = visit(node)
            override fun close() = Unit
        }
        val imported = convertRikkaChat(source, chat, Uuid.random(), { part, _ -> part })
        assertEquals("Title", imported.title)
        assertEquals(1, imported.messageNodes.single().selectIndex)
        assertEquals(listOf("first", "second"), imported.messageNodes.single().messages.map { it.toText() })
        assertEquals(rikkaImportId("message", "${chat.id}/$messageId"), imported.messageNodes.single().messages[0].id)
    }

    @Test fun `malformed required structure identities and duplicate JSON keys still refuse`() {
        val invalid = listOf("{}", "[null]", "[{\"role\":\"unknown\",\"parts\":[]}]",
            "[{\"role\":\"user\",\"parts\":{}}]", "[{\"role\":\"user\",\"parts\":[null]}]",
            "[{\"role\":\"user\",\"parts\":[{\"type\":\"text\",\"text\":{}}]}]",
            "[{\"role\":\"user\",\"parts\":[{\"type\":\"image\"}]}]",
            "[{\"role\":\"user\",\"role\":\"assistant\",\"parts\":[]}]",
            "[{\"id\":\"invalid\",\"role\":\"user\",\"parts\":[]}]",
            "[{\"id\":42,\"role\":\"user\",\"parts\":[]}]")
        invalid.forEach { assertTrue(runCatching { decodeRikkaNode(row(it)) }.isFailure) }
        val repeated = """[{"id":"$messageId","role":"user","parts":[]},{"id":"$messageId","role":"assistant","parts":[]}]"""
        assertTrue(runCatching { decodeRikkaNode(row(repeated)) }.isFailure)
        assertTrue(runCatching { decodeRikkaNode(row("[]", 1)) }.isFailure)
    }

    @Test fun `source size branch count and deep optional fields remain bounded`() {
        val many = JsonArray(List(RikkaChatLimits.MAX_NODE_BRANCHES + 1) {
            buildJsonObject { put("role", "user"); put("parts", JsonArray(emptyList())) }
        }).toString()
        assertEquals(ArchiveFailure.NODE_LIMIT, ArchiveCapacity.reasonOf(requireNotNull(runCatching { decodeRikkaNode(row(many)) }.exceptionOrNull())))
        val oversized = """[{"role":"user","parts":[{"type":"text","text":"${"x".repeat(RikkaChatLimits.MAX_NODE_BYTES)}"}]}]"""
        assertEquals(ArchiveFailure.NODE_LIMIT, ArchiveCapacity.reasonOf(requireNotNull(runCatching { decodeRikkaNode(row(oversized)) }.exceptionOrNull())))
        val deep = "[".repeat(60) + "0" + "]".repeat(60)
        val nestedMetadata = """[{"role":"user","parts":[],"metadata":$deep}]"""
        assertTrue(runCatching { decodeRikkaNode(row(nestedMetadata)) }.isFailure)
    }

    @Test fun `branch and cross node identity errors have the sanitized message stage`() = runBlocking {
        val raw = """[{"id":"$messageId","role":"user","parts":[]}]"""
        val repeatedBranch = """[{"id":"$messageId","role":"user","parts":[]},{"id":"$messageId","role":"assistant","parts":[]}]"""
        for (badNode in listOf(row(raw, 1), row(repeatedBranch), row(raw, id = "not-a-source-id"))) {
            val failure = requireNotNull(runCatching { decodeRikkaNode(badNode) }.exceptionOrNull())
            assertTrue(failure is RikkaChatReadException)
            assertTrue(ArchiveCapacity.publicError(failure).contains("RIKKA_MESSAGE"))
        }
        val chat = RikkaChatSnapshotReader.Chat("33333333-3333-4333-8333-333333333333", "Synthetic", 1, 2)
        val source = object : RikkaChatSource {
            override fun conversations() = listOf(chat)
            override suspend fun visitNodes(conversationId: String, visit: suspend (RikkaChatSnapshotReader.Node) -> Unit) {
                visit(row(raw))
                visit(row(raw, id = "44444444-4444-4444-8444-444444444444"))
            }
            override fun close() = Unit
        }
        val failure = requireNotNull(runCatching { inspectRikkaSource(source, "synthetic") }.exceptionOrNull())
        assertTrue(failure is RikkaChatReadException)
        assertTrue(ArchiveCapacity.publicError(failure).contains("RIKKA_MESSAGE"))
    }
}
