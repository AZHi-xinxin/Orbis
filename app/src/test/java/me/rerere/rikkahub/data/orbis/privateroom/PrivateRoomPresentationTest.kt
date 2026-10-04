package me.rerere.rikkahub.data.orbis.privateroom

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.ai.ui.DeletedToolRecord
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.contextpruning.ContextPruningDisplayProjection
import me.rerere.rikkahub.data.favorite.favoriteSnapshot
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.data.model.NodeFavoriteTarget
import me.rerere.rikkahub.data.model.buildFavoritePreview
import me.rerere.rikkahub.data.model.toMessageNode
import me.rerere.rikkahub.ui.pages.chat.orbisCallFiles
import me.rerere.rikkahub.ui.pages.chat.prunedContextFiles
import me.rerere.rikkahub.ui.pages.chat.prunedContextMedia
import me.rerere.rikkahub.ui.pages.orbis.manualContextMessageSnippet
import org.junit.Assert.*
import org.junit.Test
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.uuid.Uuid

class PrivateRoomPresentationTest {
    private val secret = "SYNTHETIC_PRIVATE_BODY_NEVER_DISPLAY"
    private val publicReply = "已完成操作；如果需要恢复，请打开设置。"
    // UIMessage has clock-derived defaults; always encode them for stable before/after comparisons.
    private val stableJson = Json { encodeDefaults = true }
    private fun tool(name: String = "orbis_private_room_read") = UIMessagePart.Tool(
        "synthetic-call", name, "{\"body\":\"$secret\"}", listOf(UIMessagePart.Text(secret)))
    private fun message(part: UIMessagePart = tool()) = UIMessage.assistant(publicReply).copy(
        parts = listOf(part, UIMessagePart.Text(publicReply), UIMessagePart.Reasoning(secret)), translation = secret)
    private fun assertRecordsHidden(message: UIMessage, expected: String = publicReply) {
        val visible = message.privateRoomSafePresentation()
        assertEquals(expected, visible.toText())
        assertEquals(1, visible.parts.size)
        assertFalse(visible.privateRoomContentHidden)
        assertFalse(visible.privateRoomPendingPresentation)
        assertFalse(visible.hasPrivateRoomToolContent())
        assertFalse(Json.encodeToString(visible).contains(secret))
    }

    @Test fun allSevenLocalToolsHideRecordsButKeepPublicReplyAndOriginalContext() {
        for (name in listOf("visit", "list", "read", "write", "requests", "decide", "revoke")) {
            val source = message(tool("orbis_private_room_$name"))
            val originalParts = source.parts
            val serialized = stableJson.encodeToString(source)
            assertRecordsHidden(source)
            assertEquals(serialized, stableJson.encodeToString(source))
            assertSame(originalParts, source.parts)
            assertEquals(secret, (source.getTools().single().output.single() as UIMessagePart.Text).text)
        }
    }

    @Test fun partialInvalidJsonAndDeniedFailureTextCannotEscape() {
        assertRecordsHidden(message(tool().copy(input = "{\"body\":\"$secret", output = emptyList())))
        assertRecordsHidden(message(tool().copy(approvalState = ToolApprovalState.Denied(secret))))
    }

    @Test fun streamedPendingPresentationKeepsReplyBeforeTheToolNameIsComplete() {
        assertRecordsHidden(message(tool("orbis_priv").copy(input = secret)).copy(privateRoomPendingPresentation = true))
        val historicalPending = UIMessage.assistant(publicReply).copy(privateRoomPendingPresentation = true)
        assertSame(historicalPending, historicalPending.privateRoomSafePresentation())
    }

    @Test fun previouslyHiddenContinuationDisplaysTextWithoutDatabaseMigration() {
        assertRecordsHidden(UIMessage.assistant(publicReply).copy(privateRoomContentHidden = true,
            parts = listOf(UIMessagePart.Text(publicReply), UIMessagePart.Reasoning(secret))))
    }

    @Test fun unrelatedMcpNamesPrefixesAndLiteralProseStayUnchanged() {
        for (name in listOf("mcp__server__orbis_private_room_read", "orbis_private_room_read_more",
            "orbis_private_room", "private_read", "ORBiS_PRIVATE_ROOM_READ")) {
            val source = message(tool(name))
            assertFalse(isPrivateRoomToolName(name))
            assertFalse(source.hasPrivateRoomToolContent())
            assertSame(source, source.privateRoomSafePresentation())
        }
        val prose = UIMessage.assistant("orbis_private_room_read is just mentioned")
        assertSame(prose, prose.privateRoomSafePresentation())
    }

    @Suppress("DEPRECATION")
    @Test fun legacyLocalToolPartsAreAlsoHiddenWithoutParsingTheirPayload() {
        assertRecordsHidden(message(UIMessagePart.ToolCall("old", "orbis_private_room_write", secret)))
        assertRecordsHidden(message(UIMessagePart.ToolResult("old", "orbis_private_room_read", JsonPrimitive(secret), JsonPrimitive(secret))))
    }

    @Test fun deletingAToolKeepsItsRecordHiddenButRestoresThePublicReply() {
        val original = UIMessage.assistant(publicReply).copy(parts = listOf(
            UIMessagePart.Text(publicReply), UIMessagePart.Reasoning(secret)))
        val deleted = original.copy(deletedToolRecords = listOf(DeletedToolRecord(tool(), 1, original.createdAt)))
        assertRecordsHidden(deleted)
        assertEquals(secret, (deleted.deletedToolRecords.single().tool.output.single() as UIMessagePart.Text).text)
        assertTrue(deleted.privateRoomSafePresentation().deletedToolRecords.isEmpty())
    }

    @Test fun topLevelPublicAttachmentsStayWhileToolAttachmentsAndOldTranslationDoNot() {
        val source = message().let { it.copy(parts = it.parts + listOf(
            UIMessagePart.Image("file:///public.png"), UIMessagePart.Audio("file:///public.wav"),
            UIMessagePart.Document("file:///public.txt", "public.txt"), UIMessagePart.Video("file:///public.mp4"))) }
        val visible = source.privateRoomSafePresentation()
        assertEquals(5, visible.parts.size)
        assertEquals(publicReply, (visible.parts.first() as UIMessagePart.Text).text)
        assertFalse(Json.encodeToString(visible).contains(secret))
        assertNull(visible.translation)
        assertEquals(7, source.parts.size)
        assertEquals(secret, source.translation)
    }

    @Test fun projectionIsIdempotentAndKeepsStableMessageAndBranchIdentity() {
        val source = message()
        val safe = source.privateRoomSafePresentation()
        assertEquals(safe, safe.privateRoomSafePresentation())
        assertEquals(source.id, safe.id)
        assertEquals(source.createdAt, safe.createdAt)
        val public = UIMessage.assistant("visible")
        val node = MessageNode(messages = listOf(public, source), selectIndex = 0)
        val visible = node.privateRoomSafePresentation()
        assertEquals(node.id, visible.id)
        assertEquals(0, visible.selectIndex)
        assertSame(public, visible.messages[0])
        assertEquals(publicReply, visible.messages[1].toText())
        assertSame(source, node.messages[1])
    }

    @Test fun favoritePreviewAndSavedSnapshotContainPublicReplyNotPrivateRecords() {
        val source = message().toMessageNode()
        val originalText = source.currentMessage.toText()
        assertEquals(publicReply, source.buildFavoritePreview())
        val target = NodeFavoriteTarget(Uuid.random(), "public title", source.id, source)
        val favorite = target.favoriteSnapshot()
        assertEquals(publicReply, favorite.parts.single().text)
        assertFalse(Json.encodeToString(favorite).contains(secret))
        assertEquals(originalText, source.currentMessage.toText())
        assertEquals(publicReply, (source.currentMessage.parts[1] as UIMessagePart.Text).text)
    }

    @Test fun callAttachmentExtractionDoesNotExposePrivateOrSameTurnPublicToolFiles() {
        val document = UIMessagePart.Document("file:///synthetic/upload/$secret.txt", secret)
        val source = message(tool().copy(output = listOf(document))).toMessageNode()
        assertTrue(orbisCallFiles(listOf(source)).isEmpty())
        val publicDocument = UIMessagePart.Document("file:///synthetic/upload/public.txt", "public.txt")
        val marked = UIMessage.assistant("").copy(privateRoomContentHidden = true, parts = listOf(publicDocument)).toMessageNode()
        assertEquals(1, orbisCallFiles(listOf(marked)).size)
    }

    @Test fun prunedFileAndMediaRetentionCannotReExposePrivateOutputs() {
        val part = tool().copy(output = listOf(UIMessagePart.Image("file:///$secret.png"),
            UIMessagePart.Document("file:///synthetic/upload/$secret.txt", secret)))
        val source = message(part).toMessageNode()
        val hidden = ContextPruningDisplayProjection(hiddenToolIndexes = setOf(0))
        assertTrue(prunedContextMedia(source, hidden).isEmpty())
        assertTrue(prunedContextFiles(source, hidden).isEmpty())
    }

    @Test fun manualHistoryBoundaryPreviewContainsPublicReplyButNoToolDetails() {
        assertEquals(publicReply, manualContextMessageSnippet(message().toMessageNode()))
        assertEquals("normal preview", manualContextMessageSnippet(UIMessage.assistant("normal preview").toMessageNode()))
    }

    @Test fun toolOnlyOrReasoningOnlyTurnsHaveAnInertNoticeNotTheirPayload() {
        assertRecordsHidden(UIMessage.assistant("").copy(parts = listOf(tool())), PRIVATE_ROOM_CONTENT_HIDDEN)
        assertRecordsHidden(UIMessage.assistant("").copy(privateRoomContentHidden = true,
            parts = listOf(UIMessagePart.Reasoning(secret))), PRIVATE_ROOM_CONTENT_HIDDEN)
    }

    @Test fun successfulErrorAndGuidanceTextAreAllPublicEvenWhenOldFlagsAreSet() {
        for (text in listOf("保存成功", "操作失败，请先完成恢复", "打开设置后点确认")) {
            assertRecordsHidden(message().copy(parts = listOf(UIMessagePart.Text(text), tool()),
                privateRoomContentHidden = true, privateRoomPendingPresentation = true), text)
        }
    }

    @Test fun closedAndUnclosedThinkTagsNeverBecomePublicProse() {
        for (pending in listOf(false, true)) {
            val source = message().copy(privateRoomPendingPresentation = pending,
                parts = listOf(tool(), UIMessagePart.Reasoning(secret),
                    UIMessagePart.Text("<think>$secret</think>$publicReply")))
            assertRecordsHidden(source)
            assertRecordsHidden(source.copy(parts = listOf(tool(), UIMessagePart.Text("<think>$secret"))),
                PRIVATE_ROOM_CONTENT_HIDDEN)
            assertRecordsHidden(source.copy(parts = listOf(tool(), UIMessagePart.Text("<thi"))),
                PRIVATE_ROOM_CONTENT_HIDDEN)
        }
    }

    @Test fun publicTextMetadataCannotExposePrivateDiagnostics() {
        val metadata = kotlinx.serialization.json.buildJsonObject { put("private_debug", JsonPrimitive(secret)) }
        val source = message().copy(parts = listOf(UIMessagePart.Text(publicReply, metadata), tool()))
        assertRecordsHidden(source)
        assertSame(metadata, source.parts.first().metadata)
    }

    @Test fun ordinaryFollowupIsUnchangedAfterProjectingAPrivateOperation() {
        message().privateRoomSafePresentation()
        val followup = UIMessage.assistant("接下来正常聊天。")
        assertSame(followup, followup.privateRoomSafePresentation())
    }

    @Test fun secondaryOutputsCanOmitAnEmptyNoticeWithoutDroppingLiteralPublicText() {
        for (text in listOf("<think>$secret</think>", "<think>$secret", "<thi")) {
            val parts = privateRoomPublicParts(listOf(UIMessagePart.Text(text), tool()), includePlaceholder = false)
            assertTrue(parts.filterIsInstance<UIMessagePart.Text>().all { it.text.isBlank() })
        }
        assertEquals(PRIVATE_ROOM_CONTENT_HIDDEN,
            (privateRoomPublicParts(listOf(UIMessagePart.Text(PRIVATE_ROOM_CONTENT_HIDDEN)),
                includePlaceholder = false).single() as UIMessagePart.Text).text)
    }

    @Test fun onlyReasoningAfterTheFirstPrivateToolIsHiddenAndEarlierTimingIsUnchanged() {
        val start = Instant.parse("2026-10-04T01:00:00Z")
        val before = UIMessagePart.Reasoning("public reasoning before room", start, start + 3.seconds)
        val source = message().copy(parts = listOf(before, UIMessagePart.Text("before reply"), tool(),
            UIMessagePart.Reasoning(secret), UIMessagePart.Text(publicReply)), privateRoomContentHidden = true)
        val original = stableJson.encodeToString(source)
        val visible = source.privateRoomSafePresentation()
        assertSame(before, visible.parts.first())
        assertEquals(listOf("before reply", publicReply), visible.parts.filterIsInstance<UIMessagePart.Text>().map { it.text })
        assertEquals(listOf(before), visible.parts.filterIsInstance<UIMessagePart.Reasoning>())
        assertFalse(stableJson.encodeToString(visible).contains(secret))
        assertEquals(original, stableJson.encodeToString(source))
    }

    @Test fun ordinaryToolsBeforeAndAfterPrivateToolStayVisibleAndKeepTheirIdentity() {
        val before = tool("normal_before").copy(input = "before input")
        val after = tool("normal_after").copy(input = "after input")
        val source = message().copy(parts = listOf(before, tool(), UIMessagePart.Reasoning("room reasoning"),
            after, UIMessagePart.Text(publicReply)))
        val visible = source.privateRoomSafePresentation()
        assertEquals(listOf(before, after), visible.getTools())
        assertSame(before, visible.getTools()[0])
        assertSame(after, visible.getTools()[1])
        assertTrue(visible.parts.none { it is UIMessagePart.Reasoning })
        assertEquals(visible, visible.privateRoomSafePresentation())
    }

    @Test fun potentialNamesProtectOnlyAnActualUnfinishedToolNotOrdinaryHistoricalPendingFlags() {
        for (name in listOf("", "o", "orbis_pr", "orbis_private_room_read")) assertTrue(isPotentialPrivateRoomToolName(name))
        for (name in listOf("search_web", "mcp__orbis_private_room_read", "orbis_private_room_reader"))
            assertFalse(isPotentialPrivateRoomToolName(name))
        val before = UIMessagePart.Reasoning("ordinary reasoning")
        val source = message().copy(privateRoomPendingPresentation = true,
            parts = listOf(before, tool("orbis_pr"), UIMessagePart.Reasoning(secret), UIMessagePart.Text(publicReply)))
        val visible = source.privateRoomSafePresentation()
        assertSame(before, visible.parts.first())
        assertEquals(listOf(before), visible.parts.filterIsInstance<UIMessagePart.Reasoning>())
        assertFalse(stableJson.encodeToString(visible).contains(secret))
        for (parts in listOf(listOf(before, UIMessagePart.Text(publicReply)), listOf(before, tool("search_web")))) {
            val old = source.copy(parts = parts)
            assertFalse(old.hasPrivateRoomToolContent())
            assertSame(old, old.privateRoomSafePresentation())
        }
    }

    @Test fun thinkingTagsBeforeRoomAreReasoningNotIndexablePublicTextAndLaterTagsAreHidden() {
        val source = message().copy(parts = listOf(
            UIMessagePart.Text("<think>ordinary tagged reasoning</think>before reply"), tool(),
            UIMessagePart.Text("<think>$secret</think>$publicReply")))
        val visible = source.privateRoomSafePresentation()
        assertEquals("ordinary tagged reasoning", visible.parts.filterIsInstance<UIMessagePart.Reasoning>().single().reasoning)
        assertEquals(listOf("before reply", publicReply), visible.parts.filterIsInstance<UIMessagePart.Text>().map { it.text })
        assertFalse(visible.toText().contains("ordinary tagged reasoning"))
        assertFalse(stableJson.encodeToString(visible).contains(secret))
        val derived = privateRoomPublicParts(source.parts, includePlaceholder = false)
            .filterIsInstance<UIMessagePart.Text>().joinToString { it.text }
        assertFalse(derived.contains("reasoning"))
        assertFalse(derived.contains(secret))
    }

    @Test fun unfinishedThinkBeforePrivateCallKeepsItsReasoningButNotItsDelimiterAsProse() {
        val source = message().copy(parts = listOf(UIMessagePart.Text("<think>ordinary unfinished thought"), tool()))
        val visible = source.privateRoomSafePresentation()
        val thought = visible.parts.filterIsInstance<UIMessagePart.Reasoning>().single()
        assertEquals("ordinary unfinished thought", thought.reasoning)
        assertNull(thought.finishedAt)
        assertTrue(visible.parts.filterIsInstance<UIMessagePart.Text>().all { it.text.isBlank() })
    }

    @Test fun ordinaryUndoRecordsAndPrunedAttachmentsAreNotHiddenByAnotherPrivateTool() {
        val normal = tool("normal_attachment").copy(output = listOf(UIMessagePart.Image("file:///public-image.png")))
        val source = message().copy(parts = listOf(tool(), normal), deletedToolRecords = listOf(
            DeletedToolRecord(normal, 1, message().createdAt), DeletedToolRecord(tool(), 0, message().createdAt)))
        val visible = source.privateRoomSafePresentation()
        assertEquals(listOf(normal), visible.deletedToolRecords.map { it.tool })
        val media = prunedContextMedia(source.toMessageNode(), ContextPruningDisplayProjection(hiddenToolIndexes = setOf(0, 1)))
        assertEquals(listOf(UIMessagePart.Image("file:///public-image.png")), media)
    }
}
