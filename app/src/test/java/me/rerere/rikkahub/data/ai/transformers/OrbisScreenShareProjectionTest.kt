package me.rerere.rikkahub.data.ai.transformers

import kotlinx.coroutines.runBlocking
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.*
import org.junit.Test

class OrbisScreenShareProjectionTest {
    private val session = "00000000-0000-4000-8000-000000000001"
    private val frame = "00000000-0000-4000-8000-000000000002"
    private val uri get() = "orbis-screen-frame://$session/$frame"
    private fun tool() = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(
        UIMessagePart.Tool("peek", "orbis_screen_peek_now", "{}", listOf(UIMessagePart.Image(uri)))))

    @Test fun currentToolGetsTransientImageWithoutChangingStoredSource() = runBlocking {
        val messages = listOf(UIMessage.user("看看"), tool())
        val result = projectScreenShareHandles(messages) { suppliedSession, suppliedFrame ->
            assertEquals(session, suppliedSession); assertEquals(frame, suppliedFrame); "data:image/jpeg;base64,test"
        }
        assertEquals(uri, ((messages.last().parts.single() as UIMessagePart.Tool).output.single() as UIMessagePart.Image).url)
        assertEquals("data:image/jpeg;base64,test", ((result.last().parts.single() as UIMessagePart.Tool).output.single() as UIMessagePart.Image).url)
    }

    @Test fun oldToolNeverReadsScreenAgain() = runBlocking {
        var reads = 0
        val result = projectScreenShareHandles(listOf(UIMessage.user("之前"), tool(), UIMessage.user("新的问题"))) { _, _ -> reads++; "forbidden" }
        assertEquals(0, reads)
        assertTrue((result[1].parts.single() as UIMessagePart.Tool).output.single() is UIMessagePart.Text)
    }

    @Test fun pausedOrStoppedSessionCannotRehydrateFrame() = runBlocking {
        val result = projectScreenShareHandles(listOf(UIMessage.user("看"), tool())) { _, _ -> null }
        assertTrue((result.last().parts.single() as UIMessagePart.Tool).output.single() is UIMessagePart.Text)
    }

    @Test fun malformedHandleAndNoUserNeverResolve() = runBlocking {
        var reads = 0
        projectScreenShareHandles(listOf(tool())) { _, _ -> reads++; "forbidden" }
        projectScreenShareHandles(listOf(UIMessage.user("看").copy(parts = listOf(UIMessagePart.Image("orbis-screen-frame://../../file"))))) { _, _ -> reads++; "forbidden" }
        assertEquals(0, reads)
    }

    @Test fun ordinaryAttachmentsRemainUnchanged() = runBlocking {
        val input = listOf(UIMessage.user("图").copy(parts = listOf(UIMessagePart.Image("file:///private/image.jpg"))))
        assertEquals(input, projectScreenShareHandles(input) { _, _ -> error("not a screen frame") })
    }

    @Test fun generationSnapshotKeepsOriginalFrameAndSummaryAcrossToolContinuation() = runBlocking {
        val initial = me.rerere.rikkahub.service.ScreenShareState(session, "owner", "conversation", lastFrameId = frame, summary = "原摘要")
        val turn = ScreenShareTurn.bind(initial, "owner", "conversation")
        val current = initial.copy(lastFrameId = "new-frame", summary = "新的摘要")
        assertNotEquals(initial, current)
        val request = listOf(UIMessage.user("问题"))
        val first = projectScreenShareTurn(turn, request, true) { requestedSession, requestedFrame ->
            assertEquals(session, requestedSession); assertEquals(frame, requestedFrame); "fixed-image"
        }
        val next = projectScreenShareTurn(turn, request + UIMessage.assistant("工具继续"), true) { _, requestedFrame ->
            assertEquals(frame, requestedFrame); "fixed-image"
        }
        assertEquals(first.first(), next.first())
        assertTrue(first.first().toText().contains("原摘要"))
        assertFalse(first.first().toText().contains("新的摘要"))
        assertEquals(request.first().parts.size, 1)
    }

    @Test fun revokedGenerationSnapshotStopsUploadInsteadOfReplacingPrefix() = runBlocking {
        val turn = ScreenShareTurn(session, "owner", "conversation", frame, "", true)
        try {
            projectScreenShareTurn(turn, listOf(UIMessage.user("问题")), true) { _, _ -> null }
            fail("Revocation must stop request")
        } catch (_: ScreenShareFrameRevokedException) { }
    }

    @Test fun pausedSnapshotCannotAttachNewFrameAfterResume() = runBlocking {
        val turn = ScreenShareTurn(session, "owner", "conversation", null, "", false)
        var reads = 0
        val result = projectScreenShareTurn(turn, listOf(UIMessage.user("问题")), true) { _, _ -> reads++; "new-image" }
        assertEquals(0, reads)
        assertFalse(result.first().parts.any { it is UIMessagePart.Image })
    }
}
