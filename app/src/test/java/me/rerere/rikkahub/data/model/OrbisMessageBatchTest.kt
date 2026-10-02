package me.rerere.rikkahub.data.model

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.compaction.isCompactionSummary
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

/** Synthetic immutable data only; no provider, tool executor, file or database. */
@Suppress("DEPRECATION")
class OrbisMessageBatchTest {
    private fun source() = Conversation(assistantId = Uuid.random(), title = "synthetic",
        messageNodes = List(4) { UIMessage.user("synthetic $it").toMessageNode() })
    private fun call(id: String) = UIMessage.assistant("").copy(parts = listOf(UIMessagePart.ToolCall(id, "noop", "{}"))).toMessageNode()
    private fun result(id: String) = UIMessage(role = MessageRole.TOOL,
        parts = listOf(UIMessagePart.ToolResult(id, "noop", JsonPrimitive("synthetic"), JsonObject(emptyMap())))).toMessageNode()
    private fun prepare(c: Conversation, ids: Set<Uuid>, op: OrbisMessageBatchOperation = OrbisMessageBatchOperation.DELETE) =
        prepareOrbisMessageBatch(c, ids, op).also { validateOrbisMessageBatch(c, it) }
    private fun invalidated(node: MessageNode) = node.copy(messages = node.messages.map { it.copy(usageContextInvalidated = true) })

    @Test fun nonContiguousDeletePreservesOrderAndOriginalSnapshot() {
        val c = source(); val before = manualContextFingerprint(c)
        val p = prepare(c, setOf(c.messageNodes[0].id, c.messageNodes[2].id))
        assertEquals(listOf(c.messageNodes[1], c.messageNodes[3]).map(::invalidated), p.replacementNodes)
        assertEquals(2, p.affectedCount); assertEquals(0, p.additionalCount)
        assertEquals(before, manualContextFingerprint(c)); assertNull(p.archiveMetadata)
    }

    @Test fun deleteWholePageIsAllowedAndDoesNotInventSummary() {
        val c = source(); val p = prepare(c, c.messageNodes.map { it.id }.toSet())
        assertTrue(p.replacementNodes.isEmpty()); assertEquals(0L, p.afterTokens)
    }

    @Test fun emptyUnknownOrStaleSelectionIsRejected() {
        val c = source()
        listOf(emptySet(), setOf(Uuid.random()), setOf(c.messageNodes.first().id, Uuid.random())).forEach {
            assertTrue(runCatching { prepare(c, it) }.isFailure)
        }
    }

    @Test fun allAnswerAlternativesTravelWithTheirNode() {
        val node = MessageNode(messages = listOf(UIMessage.assistant("a"), UIMessage.assistant("b")), selectIndex = 1)
        val c = source().let { it.copy(messageNodes = it.messageNodes + node) }
        val p = prepare(c, setOf(node.id))
        assertEquals(1, p.alternativeCount); assertFalse(p.replacementNodes.any { it.id == node.id })
        assertEquals("b", c.messageNodes.last().currentMessage.toText())
    }

    @Test fun pairedLegacyToolsExpandSelectionButNotUnrelatedMiddleText() {
        val a = call("c"); val b = result("c"); val middle = UIMessage.user("middle").toMessageNode()
        val c = source().copy(messageNodes = listOf(a, middle, b))
        val p = prepare(c, setOf(b.id))
        assertEquals(setOf(a.id, b.id), p.affectedNodeIds); assertEquals(1, p.additionalCount)
        assertEquals(listOf(invalidated(middle)), p.replacementNodes)
    }

    @Test fun toolClosureIsTransitiveAcrossAlternativeBranches() {
        val first = call("a"); val last = result("b")
        val bridge = MessageNode(messages = listOf(result("a").currentMessage, call("b").currentMessage))
        // A complete current branch is required too; combined tool b closes its selected result.
        val completed = UIMessage.assistant("").copy(parts = listOf(UIMessagePart.Tool("b", "noop", "{}", listOf(UIMessagePart.Text("ok"))))).toMessageNode()
        val c = source().copy(messageNodes = listOf(first, bridge, completed, last))
        val p = prepare(c, setOf(first.id))
        assertEquals(c.messageNodes.map { it.id }.toSet(), p.affectedNodeIds)
        assertEquals(3, p.additionalCount)
    }

    @Test fun completeVoiceCallGroupIsExplicitlyIncluded() {
        val nodes = listOf(UIMessage.user("turn1"), UIMessage.assistant("turn2"))
            .map { it.copy(orbisVoiceCallId = "synthetic-call", orbisVoiceCallKind = "turn").toMessageNode() }
        val c = source().let { it.copy(messageNodes = nodes + it.messageNodes) }
        val p = prepare(c, setOf(nodes[1].id))
        assertEquals(nodes.map { it.id }.toSet(), p.affectedNodeIds); assertEquals(1, p.voiceCallCount)
    }

    @Test fun pendingToolsAndOrphanResultsBlockRatherThanExecuteOrRepair() {
        val pending = UIMessage.assistant("").copy(parts = listOf(UIMessagePart.Tool("pending", "noop", "{}", emptyList()))).toMessageNode()
        listOf(pending, result("orphan"), call("orphan")).forEach { invalid ->
            val c = source().let { it.copy(messageNodes = it.messageNodes + invalid) }
            assertTrue(runCatching { prepare(c, setOf(c.messageNodes.first().id)) }.isFailure)
        }
    }

    @Test fun toolPairOnlyInAnUnselectedBranchDoesNotMakeCurrentPathValid() {
        val a = call("c")
        val b = MessageNode(messages = listOf(UIMessage.assistant("selected text"), result("c").currentMessage))
        val c = source().copy(messageNodes = listOf(a, b))
        assertTrue(runCatching { prepare(c, setOf(a.id)) }.isFailure)
    }

    @Test fun retainedAlternativesKeepUsageContentAndIdsButInvalidateBudgetAnchors() {
        val branch = MessageNode(messages = listOf(UIMessage.assistant("a").copy(usage = TokenUsage(promptTokens = 10)),
            UIMessage.assistant("b").copy(usage = TokenUsage(promptTokens = 20))), selectIndex = 1)
        val c = source().let { it.copy(messageNodes = it.messageNodes + branch) }
        val kept = prepare(c, setOf(c.messageNodes.first().id)).replacementNodes.last()
        assertEquals(invalidated(branch), kept)
        assertEquals(branch.messages.map { it.usage }, kept.messages.map { it.usage })
        assertTrue(kept.messages.all { it.usageContextInvalidated })
        assertEquals(20, branch.currentMessage.usage!!.promptTokens)
    }

    @Test fun archiveIsOnlyFactualNoticeAndRetainedNodesNotAnInventedSummary() {
        val c = source(); val p = prepare(c, setOf(c.messageNodes[1].id), OrbisMessageBatchOperation.ARCHIVE)
        val notice = p.replacementNodes.first().currentMessage
        assertTrue(notice.isCompactionSummary()); assertEquals(1, notice.parts.size)
        assertTrue(notice.parts.single() is UIMessagePart.Text)
        assertEquals(JsonPrimitive(false), notice.parts.single().metadata?.get("generated_summary"))
        assertTrue(notice.toText().contains("没有生成或补写摘要"))
        assertEquals(c.messageNodes.filterIndexed { index, _ -> index != 1 }.map(::invalidated), p.replacementNodes.drop(1))
    }

    @Test fun wholePageArchiveLeavesOnlyTheFactualNotice() {
        val c = source(); val p = prepare(c, c.messageNodes.map { it.id }.toSet(), OrbisMessageBatchOperation.ARCHIVE)
        assertEquals(1, p.replacementNodes.size); assertEquals(0, p.archiveMetadata!!.keepRecent)
    }

    @Test fun alteredPreviewCannotChangeUnselectedTextOrInjectATool() {
        val c = source(); val p = prepare(c, setOf(c.messageNodes.first().id), OrbisMessageBatchOperation.ARCHIVE)
        val fake = p.replacementNodes.first().let { n -> n.copy(messages = listOf(n.currentMessage.copy(parts = listOf(
            UIMessagePart.Tool("fake", "noop", "{}", listOf(UIMessagePart.Text("fake"))))))) }
        val changedTail = p.replacementNodes.last().let { n -> n.copy(messages = listOf(n.currentMessage.copy(parts = listOf(UIMessagePart.Text("changed"))))) }
        listOf(p.copy(replacementNodes = listOf(fake) + p.replacementNodes.drop(1)),
            p.copy(replacementNodes = p.replacementNodes.dropLast(1) + changedTail),
            p.copy(alternativeCount = 10), p.copy(afterTokens = 0L)).forEach {
            assertTrue(runCatching { validateOrbisMessageBatch(c, it) }.isFailure)
        }
    }

    @Test fun changedOwnerEpochBranchAndNewMessageRejectAnOldConfirmation() {
        val c = source(); val p = prepare(c, setOf(c.messageNodes.first().id))
        listOf(c.copy(assistantId = Uuid.random()), c.copy(compactionEpoch = 1),
            c.copy(messageNodes = c.messageNodes + UIMessage.user("later").toMessageNode()),
            c.copy(messageNodes = c.messageNodes.dropLast(1) + c.messageNodes.last().let { n -> n.copy(messages = n.messages + UIMessage.assistant("alternative"), selectIndex = 1) })
        ).forEach { assertTrue(runCatching { validateOrbisMessageBatch(it, p) }.isFailure) }
    }

    @Test fun duplicateNodeOrMessageIdentityAndInvalidBranchAreRejected() {
        val c = source(); val node = c.messageNodes.first()
        listOf(c.copy(messageNodes = listOf(node, node)),
            c.copy(messageNodes = listOf(node, node.copy(id = Uuid.random()))),
            c.copy(messageNodes = listOf(node.copy(selectIndex = 99)))).forEach {
            assertTrue(runCatching { prepare(it, setOf(node.id)) }.isFailure)
        }
    }

    @Test fun allCurrentFilterHasNoHiddenOrPreviousSelection() {
        val c = source(); val visible = c.messageNodes.takeLast(2).map { it.id }
        assertEquals(visible.toSet(), selectAllMessagePreviewRows(visible))
        assertTrue(selectAllMessagePreviewRows(emptyList()).isEmpty())
    }
}
