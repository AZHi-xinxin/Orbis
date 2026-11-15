package me.rerere.rikkahub.data.orbis.tabletop

import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.*
import me.rerere.ai.ui.*
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import org.junit.Assert.*
import org.junit.Test

class TabletopPolicyTest {
    private fun request()=UIMessage.user("[圆桌准备编号：test-id] 准备")
    private fun assistant(vararg parts:UIMessagePart)=UIMessage(role=MessageRole.ASSISTANT,parts=parts.toList())
    private fun pause(saved:Boolean)=UIMessagePart.Tool("t","orbis_sentinel_pause","{\"rule_id\":\"r\"}",listOf(UIMessagePart.Text("{\"saved\":$saved}")))
    @Test fun proseDoesNotCountAsReceipt() {
        val r=tabletopPreparationReceipt(listOf(request(),assistant(UIMessagePart.Text("我已经暂停了五项！"))),"test-id",false)
        assertTrue(r.contains("尚无暂停成功回执"))
    }
    @Test fun onlyExactTurnSavedWritesCount() {
        val r=tabletopPreparationReceipt(listOf(assistant(pause(true)),request(),assistant(pause(true)),UIMessage.user("下一轮"),assistant(pause(true))),"test-id",false)
        assertTrue(r.contains("暂停 1 项"));assertFalse(tabletopPreparationReceipt(listOf(request(),assistant(pause(true))),"another",false).contains("暂停 1 项"))
    }
    @Test fun partialAndRestoreRemainSeparate() {
        val r=tabletopPreparationReceipt(listOf(request(),assistant(pause(false))),"test-id",false)
        assertTrue(r.contains("未完成"))
        assertTrue(tabletopPreparationReceipt(listOf(request(),assistant(pause(true))),"test-id",true).contains("尚无恢复"))
    }
}
