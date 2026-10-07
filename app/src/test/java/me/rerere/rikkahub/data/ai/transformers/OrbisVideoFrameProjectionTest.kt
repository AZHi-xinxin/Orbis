package me.rerere.rikkahub.data.ai.transformers

import kotlinx.coroutines.runBlocking
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.*
import org.junit.Test

class OrbisVideoFrameProjectionTest {
    private val call = "11111111-1111-1111-1111-111111111111"
    private val frame = "22222222-2222-2222-2222-222222222222"
    private val uri = "orbis-video-frame://$call/$frame"
    private fun tool() = UIMessage(role = MessageRole.ASSISTANT,
        parts = listOf(UIMessagePart.Tool("read1", "orbis_video_frame_read", "{}", listOf(UIMessagePart.Image(uri)))))

    @Test fun ordinaryPartsAndArchivedOrMalformedFramesNeverAskForVideoPermission() = runBlocking {
        val ordinary = UIMessage(role = MessageRole.USER, parts = listOf(
            UIMessagePart.Text("synthetic ordinary text"),
            UIMessagePart.Image("https://example.invalid/ordinary.jpg"),
            UIMessagePart.Image("data:image/jpeg;base64,SYNTHETIC"),
        ))
        val source = listOf(UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Image(uri))),
            tool(), ordinary.copy(parts = ordinary.parts + UIMessagePart.Image("orbis-video-frame://invalid")))
        val projected = projectVideoFrameHandles(source,
            allowAutomaticFrame = { error("non-current/non-video part must not consult video runtime") },
            resolve = { _, _ -> error("non-current/invalid frame must not read storage") })
        assertTrue(projected.first().parts.single() is UIMessagePart.Text)
        assertTrue(projected[1].getTools().single().output.single() is UIMessagePart.Text)
        assertEquals(ordinary.parts, projected.last().parts.take(ordinary.parts.size))
        assertTrue(projected.last().parts.last() is UIMessagePart.Text)
    }

    @Test fun backgroundDropsAutomaticImageButExplicitHistoricalReadRemainsSeparate() = runBlocking {
        val source = listOf(UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Image(uri))), tool())
        var reads = 0
        val projected = projectVideoFrameHandles(source, allowAutomaticFrame = { false }) { _, _ ->
            reads++; "data:image/jpeg;base64,SYNTHETIC"
        }
        assertEquals(1, reads)
        assertTrue(projected.first().parts.single() is UIMessagePart.Text)
        assertTrue(projected.last().getTools().single().output.single() is UIMessagePart.Image)
        assertEquals(uri, (source.first().parts.single() as UIMessagePart.Image).url)
    }

    @Test fun noCurrentUserTurnNeverRehydratesAnArchivedTool() = runBlocking {
        val projected = projectVideoFrameHandles(listOf(tool())) { _, _ -> error("archived image must not resolve") }
        assertTrue(projected.single().getTools().single().output.single() is UIMessagePart.Text)
    }

    @Test fun hydratesCurrentToolResultWithoutMutatingPersistedReceipt() = runBlocking {
        val receipt = tool()
        val source = listOf(UIMessage.user("看看刚才的画面"), receipt)
        val projected = projectVideoFrameHandles(source) { c, f ->
            assertEquals(call, c); assertEquals(frame, f); "data:image/jpeg;base64,SYNTHETIC"
        }
        assertEquals(uri, (receipt.getTools().single().output.single() as UIMessagePart.Image).url)
        assertEquals("data:image/jpeg;base64,SYNTHETIC",
            (projected.last().getTools().single().output.single() as UIMessagePart.Image).url)
    }

    @Test fun toolContinuationCanResolveNewHandleOnEveryRequest() = runBlocking {
        val prefix = listOf(UIMessage.user("看一眼"))
        assertEquals(prefix, projectVideoFrameHandles(prefix) { _, _ -> error("no image") })
        var reads = 0
        val response = projectVideoFrameHandles(prefix + tool()) { _, _ -> reads++; "data:image/jpeg;base64,FRAME" }
        assertEquals(1, reads)
        assertTrue(response.last().getTools().single().output.single() is UIMessagePart.Image)
        // A later boundary rechecks TTL, rather than retaining old base64 in its frozen input.
        val expired = projectVideoFrameHandles(prefix + tool()) { _, _ -> error("expired") }
        assertTrue(expired.last().getTools().single().output.single() is UIMessagePart.Text)
    }

    @Test fun previousRoundImagesAreNotRereadOrRepeated() = runBlocking {
        var reads = 0
        val projected = projectVideoFrameHandles(listOf(UIMessage.user("old"), tool(), UIMessage.user("new"))) {
                _, _ -> reads++; "must-not-read" }
        assertEquals(0, reads)
        assertTrue(projected[1].getTools().single().output.single() is UIMessagePart.Text)
    }

    @Test fun currentPeriodicFrameIsHydratedButUnknownPathsNeverReachResolver() = runBlocking {
        val images = listOf(UIMessagePart.Image(uri), UIMessagePart.Image("orbis-video-frame://../../secrets"),
            UIMessagePart.Image("https://example.invalid/ordinary.jpg"))
        var reads = 0
        val projected = projectVideoFrameHandles(listOf(UIMessage(role = MessageRole.USER, parts = images))) { _, _ ->
            reads++; "data:image/jpeg;base64,SYNTHETIC"
        }.single()
        assertEquals(1, reads)
        assertTrue(projected.parts[1] is UIMessagePart.Text)
        assertEquals(images[2], projected.parts[2])
        assertEquals(uri, images.first().url)
    }
}
