package me.rerere.rikkahub.data.db.fts

import kotlinx.serialization.encodeToString
import me.rerere.ai.ui.DeletedToolRecord
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.orbis.privateroom.PRIVATE_ROOM_CONTENT_HIDDEN
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.*
import org.junit.Test

class PrivateRoomFtsPresentationTest {
    private val secret = "SYNTHETIC_PRIVATE_FTS_CANARY"
    private val publicReply = "已保存，可以继续聊天。"
    private fun privateMessage(name: String = "orbis_private_room_read") = UIMessage.assistant(publicReply).copy(
        parts = listOf(UIMessagePart.Text(publicReply), UIMessagePart.Reasoning(secret), UIMessagePart.Tool("call", name, secret,
            listOf(UIMessagePart.Text(secret)))))

    @Test fun onlyPublicTextIsIndexedDuringPrivateToolTurns() {
        for (suffix in listOf("visit", "list", "read", "write", "requests", "decide", "revoke")) {
            assertEquals(publicReply, privateMessage("orbis_private_room_$suffix").extractFtsText())
            assertFalse(privateMessage("orbis_private_room_$suffix").extractFtsText().contains(secret))
        }
    }

    @Test fun pendingAndCompletedPrivateFlagsDoNotSuppressPublicText() {
        assertEquals(publicReply, UIMessage.assistant(publicReply).copy(privateRoomContentHidden = true).extractFtsText())
        assertEquals(publicReply, UIMessage.assistant(publicReply).copy(privateRoomPendingPresentation = true).extractFtsText())
    }

    @Test fun deletedToolNeverEntersIndexWhilePublicTextAndRawHistoryRemainIntact() {
        val source = privateMessage()
        val deleted = UIMessage.assistant(publicReply).copy(deletedToolRecords =
            listOf(DeletedToolRecord(source.getTools().single(), 0, source.createdAt)))
        val encoded = JsonInstant.encodeToString(listOf(deleted))
        assertEquals(publicReply, deleted.extractFtsText())
        assertEquals(publicReply, visibleFtsMessages(encoded)[deleted.id.toString()])
        assertTrue(encoded.contains(secret))
    }

    @Test fun historicalIndexEntryMustMatchCurrentVisibleOriginal() {
        val source = UIMessage.assistant(secret)
        val nowPrivate = source.copy(parts = listOf(privateMessage().getTools().single()), privateRoomContentHidden = true)
        assertEquals("", visibleFtsMessages(JsonInstant.encodeToString(listOf(nowPrivate)))[source.id.toString()])
        val nowEdited = source.copy(parts = listOf(UIMessagePart.Text("public replacement")))
        assertEquals("public replacement", visibleFtsMessages(JsonInstant.encodeToString(listOf(nowEdited)))[source.id.toString()])
        assertNotEquals(secret, visibleFtsMessages(JsonInstant.encodeToString(listOf(nowEdited)))[source.id.toString()])
    }

    @Test fun invalidMissingOversizedOrAmbiguousOriginalFailsClosed() {
        val source = UIMessage.assistant("public")
        for (encoded in listOf(null, "invalid", " ".repeat(786_433), "[".repeat(1000) + "]".repeat(1000),
            JsonInstant.encodeToString(listOf(source, source)))) {
            assertTrue(visibleFtsMessages(encoded).isEmpty())
        }
        assertNull(visibleFtsMessages("[]")[source.id.toString()])
    }

    @Test fun publicAndUnrelatedMcpMessagesKeepExistingIndexLimit() {
        assertEquals(publicReply, privateMessage("mcp__other__orbis_private_room_read").extractFtsText())
        val source = UIMessage.assistant("a".repeat(12_000))
        assertEquals(10_000, source.extractFtsText().length)
        assertEquals(source.extractFtsText(), visibleFtsMessages(JsonInstant.encodeToString(listOf(source)))[source.id.toString()])
    }

    @Test fun quotedBracketsAndEscapesDoNotTriggerDepthGuard() {
        val source = UIMessage.assistant("[".repeat(200) + "\\\"{}")
        assertEquals(source.toText(), visibleFtsMessages(JsonInstant.encodeToString(listOf(source)))[source.id.toString()])
    }

    @Test fun toolOnlyPlaceholderAndNestedResultsAreNeverSearchable() {
        val source = privateMessage().copy(parts = privateMessage().parts.drop(1))
        assertEquals("", source.extractFtsText())
        assertEquals("", visibleFtsMessages(JsonInstant.encodeToString(listOf(source)))[source.id.toString()])
        assertEquals("", source.copy(parts = listOf(UIMessagePart.Text("  ")) + source.parts).extractFtsText())
    }

    @Test fun thinkOnlyAndPartialThinkNeverIndexAPlaceholderWhileActualPublicTextStillMatches() {
        for (text in listOf("<think>$secret</think>", "<think>$secret", "<thi", "<")) {
            val continuation = UIMessage.assistant(text).copy(privateRoomContentHidden = true)
            val pendingTool = UIMessage.assistant(text).copy(privateRoomPendingPresentation = true,
                parts = listOf(UIMessagePart.Tool("partial-call", "orbis_private_room_r", "", emptyList()),
                    UIMessagePart.Text(text)))
            for (source in listOf(continuation, pendingTool)) {
                assertTrue(source.extractFtsText().isBlank())
                assertTrue(visibleFtsMessages(JsonInstant.encodeToString(listOf(source)))[source.id.toString()].orEmpty().isBlank())
            }
        }
        val public = UIMessage.assistant("<think>$secret</think>$publicReply").copy(privateRoomContentHidden = true)
        assertEquals(publicReply, public.extractFtsText())
        // A real public reply matching the label is not a synthetic placeholder.
        assertEquals(PRIVATE_ROOM_CONTENT_HIDDEN, UIMessage.assistant(PRIVATE_ROOM_CONTENT_HIDDEN)
            .copy(privateRoomContentHidden = true).extractFtsText())
    }
}
