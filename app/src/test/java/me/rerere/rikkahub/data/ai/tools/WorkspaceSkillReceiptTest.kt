package me.rerere.rikkahub.data.ai.tools

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.orbis.market.ms
import me.rerere.rikkahub.data.orbis.skills.WorkspaceSkillFailure
import org.junit.Assert.*
import org.junit.Test

class WorkspaceSkillReceiptTest {
    private fun json(parts:List<UIMessagePart>) = Json.parseToJsonElement((parts.single() as UIMessagePart.Text).text).jsonObject

    @Test fun invalidLocalInputReturnsAReceiptAndDoesNotLeakExceptionData()=runBlocking {
        val result=json(workspaceSkillReceipt { throw IllegalArgumentException("sensitive file content must stay private") })
        assertEquals("failed",result.ms("status"))
        assertEquals("skill_invalid_arguments_or_path",result.ms("reason_code"))
        assertEquals("unchanged",result.ms("binding_state"))
        assertFalse(result.toString().contains("sensitive file content"))
        val next=listOf(UIMessagePart.Text("normal reply after correcting arguments"))
        assertEquals(next,workspaceSkillReceipt { next })
    }
    @Test fun uncertainWriteKeepsItsReconciliationInstruction()=runBlocking {
        val result=json(workspaceSkillReceipt { throw WorkspaceSkillFailure("skill_binding_write_unknown","请先list核对","unknown") })
        assertEquals("unknown",result.ms("binding_state"))
        assertEquals("skill_binding_write_unknown",result.ms("reason_code"))
        assertTrue(result.ms("message").contains("list"))
    }
    @Test fun cancellationIsNotTurnedIntoASuccessOrFailureReceipt()=runBlocking {
        val cancelled=CancellationException("synthetic cancellation")
        assertSame(cancelled,runCatching { workspaceSkillReceipt { throw cancelled } }.exceptionOrNull())
    }
}
