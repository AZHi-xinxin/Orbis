package me.rerere.rikkahub.data.ai.tools

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.orbis.companiontools.ForegroundChatReceipt
import org.junit.Assert.*
import org.junit.Test

class OrbisForegroundToolTest {
    @Test fun successRequiresHostAcknowledgementAndSupportsRememberedApproval() = runTest {
        val tool=foregroundChatTool { ForegroundChatReceipt(true,"visible") }
        assertEquals("orbis_return_to_chat",tool.name)
        val args=JsonObject(emptyMap())
        assertTrue(tool.needsApproval(args))
        assertTrue(tool.hostApproval!!.rememberable)
        val result=Json.parseToJsonElement((tool.execute(args).single() as UIMessagePart.Text).text).jsonObject
        assertTrue(result.getValue("ok").jsonPrimitive.boolean)
        assertTrue(result.getValue("foreground_switch_confirmed").jsonPrimitive.boolean)
    }
    @Test fun noAcknowledgementCannotBecomeFalsePositiveToolSuccess() = runTest {
        val tool=foregroundChatTool { ForegroundChatReceipt(false,"foreground_not_observed") }
        val result=Json.parseToJsonElement((tool.execute(JsonObject(emptyMap())).single() as UIMessagePart.Text).text).jsonObject
        assertFalse(result.getValue("ok").jsonPrimitive.boolean)
        assertFalse(result.getValue("automatic_retry").jsonPrimitive.boolean)
    }
    @Test fun modelCannotChooseAnUnboundConversation() = runTest {
        val tool=foregroundChatTool { error("must not launch") }
        val result=(tool.execute(buildJsonObject { put("conversationId","other") }).single() as UIMessagePart.Text).text
        assertTrue(result.contains("invalid_arguments"))
    }
}
