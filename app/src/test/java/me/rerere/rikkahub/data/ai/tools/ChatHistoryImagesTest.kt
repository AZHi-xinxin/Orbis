package me.rerere.rikkahub.data.ai.tools

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.toMessageNode
import me.rerere.rikkahub.data.ai.shouldResetProjectedUsageEstimate
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

class ChatHistoryImagesTest {
    private val owner = Uuid.random()
    private val old = UIMessage.user("old").copy(parts = listOf(UIMessagePart.Text("old"), UIMessagePart.Image("file:///private/upload/old.png")))
    private val fresh = UIMessage.user("new").copy(parts = listOf(UIMessagePart.Text("new"), UIMessagePart.Image("file:///private/upload/new.png")))
    private fun conversation(vararg messages: UIMessage) = Conversation(assistantId = owner, messageNodes = messages.map { it.toMessageNode() })
    @Test fun projectionPreservesLocalOriginalsAndOnlyCurrentTurnSendsPixels() {
        val c = conversation(old, fresh)
        val original = c.currentMessages
        val projected = projectHistoryImages(original, owner, c.id)
        assertTrue(projected.first().parts.all { it is UIMessagePart.Text })
        assertEquals(fresh.parts, projected.last().parts)
        val code = chatImageCode(owner, c.id, old.id, (old.parts.last() as UIMessagePart.Image).url)
        assertTrue(projected.first().parts.toString().contains(code))
        assertFalse(projected.first().parts.toString().contains("/private/"))
        assertEquals(original, c.currentMessages)
        assertTrue(shouldResetProjectedUsageEstimate(original, projected))
        assertEquals(projected, projectHistoryImages(original, owner, c.id))
        assertTrue(Assistant().sendHistoryImages)
        assertTrue(Json { ignoreUnknownKeys = true }.decodeFromString<Assistant>("{}").sendHistoryImages)
    }
    @Test fun toolResultRemainsVisibleDuringContinuationAndBecomesCodeOnNextHumanTurn() {
        val tool = UIMessagePart.Tool(toolCallId = "read-1",toolName = READ_CHAT_IMAGE,input = "{}",output = listOf(old.parts.last()))
        val reply = UIMessage.assistant("").copy(parts = listOf(tool))
        val c = conversation(old, fresh, reply)
        val projected = projectHistoryImages(c.currentMessages, owner, c.id)
        assertEquals(tool, projected.last().parts.single())
        val next = projectHistoryImages(c.currentMessages + UIMessage.user("another question"), owner, c.id)
        val result = next[next.lastIndex - 1].parts.single() as UIMessagePart.Tool
        assertEquals(tool.toolCallId, result.toolCallId)
        assertEquals(tool.input, result.input)
        assertTrue(result.output.single() is UIMessagePart.Text)
        assertEquals(tool, c.currentMessages.last().parts.single())
    }
    @Test fun onDemandReadIsBoundToCurrentOwnerBranchAndHumanSwitch() = runTest {
        val c = conversation(old, fresh)
        val scope = ChatImageReadScope.capture(c,c.currentMessages.map { it.id }.toSet())
        val code = chatImageCode(owner,c.id,old.id,(old.parts.last() as UIMessagePart.Image).url)
        var current: Conversation? = c
        var enabled = true
        var reads = 0
        val tool = createChatImageReadTool(scope,{ current },{ enabled }) { reads++; listOf(it) }
        val args = buildJsonObject { put("image_code",code) }
        val success = tool.execute(args)
        assertEquals(old.parts.last(),success.last());assertEquals(1,reads)
        enabled = false;assertTrue(tool.execute(args).single() is UIMessagePart.Text);assertEquals(1,reads)
        enabled = true
        for (bad in listOf(c.copy(assistantId = Uuid.random()),c.copy(compactionEpoch = c.compactionEpoch + 1),
            c.copy(messageNodes = c.messageNodes + UIMessage.user("next").toMessageNode()),
            c.copy(messageNodes = listOf(fresh.toMessageNode())),null)) {
            current = bad;assertFalse(tool.execute(args).any { it is UIMessagePart.Image });assertEquals(1,reads)
        }
        current=c
        assertFalse(tool.execute(buildJsonObject { put("image_code","file:///private/token") }).any { it is UIMessagePart.Image })
        assertFalse(tool.execute(buildJsonObject { put("image_code","orbisimg_" + "0".repeat(64)) }).any { it is UIMessagePart.Image })
        assertEquals(1,reads)
    }
    @Test fun branchChangedWhileRecognizingImageCannotDeliverOldResult() = runTest {
        val c=conversation(old,fresh);var current=c
        val scope=ChatImageReadScope.capture(c,c.currentMessages.map{it.id}.toSet())
        val tool=createChatImageReadTool(scope,{current},{true}) { image ->
            current=c.copy(messageNodes=c.messageNodes+UIMessage.user("new turn").toMessageNode());listOf(image)
        }
        assertFalse(tool.execute(buildJsonObject{put("image_code",scope.images.keys.first())}).any{it is UIMessagePart.Image})
    }
}
