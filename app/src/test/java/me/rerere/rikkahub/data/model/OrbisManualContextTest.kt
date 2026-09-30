package me.rerere.rikkahub.data.model

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonObject
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.OrbisEventMetadata
import me.rerere.rikkahub.data.ai.compaction.isCompactionSummary
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import kotlin.uuid.Uuid

@Suppress("DEPRECATION")
class OrbisManualContextTest {
    private fun source() = Conversation(assistantId = Uuid.random(), title = "synthetic",
        messageNodes = (1..6).map { UIMessage.user("synthetic $it").toMessageNode() })

    @Test fun `preview is pure and defaults to explicit omission notice`() {
        val original = source()
        val fingerprint = manualContextFingerprint(original)
        val preview = prepareOrbisManualContext(original, 3)
        assertEquals(3, preview.archivedCount)
        assertEquals(3, preview.keptCount)
        assertEquals(original.id, preview.conversationId)
        assertEquals(original.compactionEpoch, preview.compactionEpoch)
        assertEquals(fingerprint, manualContextFingerprint(original))
        assertEquals(fingerprint, preview.expectedFingerprint)
        assertTrue(preview.summaryText.contains("没有生成或补写历史摘要"))
        val summary = preview.replacementNodes.first().currentMessage
        assertTrue(summary.isCompactionSummary())
        assertEquals(JsonPrimitive(true), summary.parts.single().metadata?.get("human_authored"))
        assertEquals(JsonPrimitive(false), summary.parts.single().metadata?.get("generated_summary"))
        validateManualContextReplacement(original, preview.replacementNodes, preview.metadata)
    }

    @Test fun `fresh previews with live reasoning timestamps remain exactly valid on repeat checks`() {
        repeat(64) {
            val original = source().let { c -> c.copy(messageNodes = c.messageNodes +
                UIMessage.assistant("synthetic reply").copy(parts = listOf(
                    UIMessagePart.Reasoning("synthetic reasoning with live default timestamps"),
                    UIMessagePart.Text("synthetic reply"),
                )).toMessageNode()) }
            val fingerprint = manualContextFingerprint(original)
            val preview = prepareOrbisManualContext(original, 3)
            repeat(8) {
                validateManualContextReplacement(original, preview.replacementNodes, preview.metadata)
                assertEquals(fingerprint, manualContextFingerprint(original))
                assertEquals(fingerprint, preview.expectedFingerprint)
            }
            assertTrue(runCatching { validateManualContextReplacement(original, preview.replacementNodes,
                preview.metadata.copy(afterTokens = preview.afterTokens + 1)) }.isFailure)
        }
    }

    @Test fun `manual summary remains exact after clear human provenance prefix`() {
        val text = "  synthetic statement\nline two  "
        val preview = prepareOrbisManualContext(source(), 2, text)
        assertTrue(preview.summaryText.endsWith(text))
        assertTrue(preview.summaryText.contains("由人类提供"))
        assertEquals(preview.summaryText, preview.metadata.summaryText)
    }

    @Test fun `all selected old history can be archived without making an empty page`() {
        val original = source()
        val result = prepareOrbisManualContext(original, original.messageNodes.size)
        assertEquals(0, result.keptCount)
        assertEquals(1, result.replacementNodes.size)
        validateManualContextReplacement(original, result.replacementNodes, result.metadata)
    }

    @Test fun `invalid range and oversized summary never silently truncate`() {
        listOf(-1, 0, 7).forEach { assertTrue(runCatching { prepareOrbisManualContext(source(), it) }.isFailure) }
        assertTrue(runCatching { prepareOrbisManualContext(source(), 1, "x".repeat(20_001)) }.isFailure)
        assertTrue(prepareOrbisManualContext(source(), 1, "x".repeat(20_000)).summaryText.endsWith("x".repeat(20_000)))
    }

    @Test fun `retained suffix keeps branches and attachments but clears every old usage anchor`() {
        val original = source().let { c -> c.copy(messageNodes = c.messageNodes.dropLast(1) + MessageNode(
            messages = listOf(UIMessage.assistant("one").copy(usage = TokenUsage(promptTokens = 99)),
                UIMessage.assistant("two").copy(parts = listOf(UIMessagePart.Image("file:///synthetic/image.png")),
                    usage = TokenUsage(promptTokens = 199))), selectIndex = 1)) }
        val result = prepareOrbisManualContext(original, 3)
        val old = original.messageNodes.last()
        val retained = result.replacementNodes.last()
        assertEquals(old.id, retained.id)
        assertEquals(old.selectIndex, retained.selectIndex)
        assertEquals(old.messages.map { it.id }, retained.messages.map { it.id })
        assertEquals(old.messages.map { it.parts }, retained.messages.map { it.parts })
        assertTrue(retained.messages.all { it.usage == null })
        assertEquals(199, old.currentMessage.usage!!.promptTokens)
    }

    @Test fun `legacy tool result expands suffix to its matching call`() {
        val call = UIMessage.assistant("").copy(parts = listOf(UIMessagePart.ToolCall("synthetic-call", "f", "{}")))
        val result = UIMessage(role = MessageRole.TOOL, parts = listOf(UIMessagePart.ToolResult("synthetic-call", "f", JsonPrimitive("ok"), JsonObject(emptyMap()))))
        val c = source().copy(messageNodes = listOf(UIMessage.user("old").toMessageNode(), call.toMessageNode(),
            UIMessage.user("between").toMessageNode(), result.toMessageNode(), UIMessage.user("new").toMessageNode()))
        val preview = prepareOrbisManualContext(c, 3)
        assertEquals(1, preview.archivedCount)
        assertEquals(2, preview.additionalProtocolMessages)
        assertEquals(call.id, preview.replacementNodes[1].currentMessage.id)
        validateManualContextReplacement(c, preview.replacementNodes, preview.metadata)
    }

    @Test fun `protocol expansion that would remove nothing is rejected`() {
        val call = UIMessage.assistant("").copy(parts = listOf(UIMessagePart.ToolCall("c", "f", "{}")))
        val result = UIMessage(role = MessageRole.TOOL, parts = listOf(UIMessagePart.ToolResult("c", "f", JsonPrimitive("ok"), JsonObject(emptyMap()))))
        val c = source().copy(messageNodes = listOf(call.toMessageNode(), result.toMessageNode()))
        assertTrue(runCatching { prepareOrbisManualContext(c, 1) }.isFailure)
    }

    @Test fun `pending tools and orphan legacy results are refused`() {
        val pending = UIMessage.assistant("").copy(parts = listOf(UIMessagePart.Tool("c", "f", "{}", emptyList())))
        val orphan = UIMessage(role = MessageRole.TOOL, parts = listOf(UIMessagePart.ToolResult("orphan", "f", JsonPrimitive("ok"), JsonObject(emptyMap()))))
        listOf(pending, orphan).forEach { message ->
            val c = source().let { it.copy(messageNodes = it.messageNodes + message.toMessageNode()) }
            assertTrue(runCatching { prepareOrbisManualContext(c, 2) }.isFailure)
        }
    }

    @Test fun `altered tail or summary provenance is refused at commit validation`() {
        val c = source(); val p = prepareOrbisManualContext(c, 2)
        assertTrue(runCatching { validateManualContextReplacement(c, p.replacementNodes.dropLast(1), p.metadata) }.isFailure)
        val fake = UIMessage.assistant(p.summaryText).copy(id = p.metadata.summaryMessageId).toMessageNode()
        assertTrue(runCatching { validateManualContextReplacement(c, listOf(fake) + p.replacementNodes.drop(1), p.metadata) }.isFailure)
        assertTrue(runCatching { validateManualContextReplacement(c, p.replacementNodes, p.metadata.copy(afterTokens = 0)) }.isFailure)
    }

    @Test fun `fingerprint observes metadata branches and selected branch changes`() {
        val c = source(); val fp = manualContextFingerprint(c)
        assertNotEquals(fp, manualContextFingerprint(c.copy(title = "different")))
        assertNotEquals(fp, manualContextFingerprint(c.copy(compactionEpoch = 1)))
        assertNotEquals(fp, manualContextFingerprint(c.copy(messageNodes = c.messageNodes.dropLast(1))))
        assertEquals(fp, manualContextFingerprint(c.copy(createAt = Instant.ofEpochMilli(c.createAt.toEpochMilli()),
            updateAt = Instant.ofEpochMilli(c.updateAt.toEpochMilli()))))
    }

    @Test fun `archive gives all nodes and branches fresh ids without changing content or attachments`() {
        val c = source().let { it.copy(messageNodes = it.messageNodes + MessageNode(messages = listOf(
            UIMessage.assistant("branch1"), UIMessage.assistant("branch2").copy(parts = listOf(UIMessagePart.Image("file:///synthetic/a.png"))))),
            compactionEpoch = 4, isPinned = true) }
        val archive = createManualContextArchive(c, Instant.now())
        assertNotEquals(c.id, archive.id)
        assertEquals(c.assistantId, archive.assistantId)
        assertEquals(c.title + " · 原文存档", archive.title)
        assertEquals(0L, archive.compactionEpoch)
        assertFalse(archive.isPinned)
        c.messageNodes.zip(archive.messageNodes).forEach { (old, fresh) ->
            assertNotEquals(old.id, fresh.id)
            assertEquals(old.selectIndex, fresh.selectIndex)
            old.messages.zip(fresh.messages).forEach { (a, b) ->
                assertNotEquals(a.id, b.id)
                assertEquals(a, b.copy(id = a.id))
            }
        }
    }

    @Test fun `independent archive strips only live event and voice bindings from every branch`() {
        val bound = UIMessage.user("synthetic original event text").copy(
            orbisEvent = OrbisEventMetadata("record", "test", "event", 123L),
            orbisVoiceCallId = "synthetic-call", orbisVoiceCallKind = "turn", usage = TokenUsage(promptTokens = 17),
            parts = listOf(UIMessagePart.Text("synthetic text"), UIMessagePart.Tool("tool-id", "noop", "{}", listOf(UIMessagePart.Text("done")))))
        val c = source().copy(messageNodes = listOf(MessageNode(messages = listOf(bound, bound.copy(id = Uuid.random())), selectIndex = 1)))
        val archive = createManualContextArchive(c, Instant.now())
        c.messageNodes.single().messages.zip(archive.messageNodes.single().messages).forEach { (a, b) ->
            assertNull(b.orbisEvent); assertNull(b.orbisVoiceCallId); assertNull(b.orbisVoiceCallKind)
            assertEquals(a.copy(id = b.id, orbisEvent = null, orbisVoiceCallId = null, orbisVoiceCallKind = null), b)
            assertEquals("tool-id", b.getTools().single().toolCallId)
            assertEquals("record", a.orbisEvent!!.recordId)
            assertEquals("synthetic-call", a.orbisVoiceCallId)
        }
    }
}
