package me.rerere.rikkahub.data.orbis.plugins

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.tools.createOrbisSentinelTools
import me.rerere.rikkahub.data.orbis.sentinel.*
import org.junit.Assert.*
import org.junit.Test

class PluginSentinelBindingTest {
    @Test fun pluginRulePersistsHostBindingAndCannotReplaceItUsingArguments() = runTest {
        val owner="00000000-0000-0000-0000-000000000001"
        val conversation="00000000-0000-0000-0000-000000000002"
        var file: String?=null
        fun store()=OrbisSentinelRuleStore(read={file},write={file=it},lock=Any())
        val store=store();store.setHumanEnabled(false,500);val origin=OrbisSentinelPlugin("plugin","approved-hash","wake")
        val tools=createOrbisSentinelTools(store,owner,conversation,now={1000},plugin=origin)
        val input=buildJsonObject {put("type","once");put("due_at_ms",2000);put("prompt","合成账本到点提醒")}
        val result=Json.parseToJsonElement((tools.first {it.name=="orbis_sentinel_create"}.execute(input).single() as UIMessagePart.Text).text).jsonObject
        assertTrue(result["saved"]!!.jsonPrimitive.boolean)
        assertFalse(result["human_master_enabled"]!!.jsonPrimitive.boolean)
        val rule=store().list().single()
        assertEquals(origin,rule.plugin);assertEquals(conversation,rule.conversationId);assertEquals(owner,rule.assistantId)
        tools.first {it.name=="orbis_sentinel_pause"}.execute(buildJsonObject {put("rule_id",rule.id)})
        assertEquals(origin,store().get(rule.id)!!.plugin)
        try {
            tools.first {it.name=="orbis_sentinel_create"}.execute(JsonObject(input+ ("conversationId" to JsonPrimitive(owner))))
            fail("JS supplied target must be rejected")
        } catch(_: me.rerere.rikkahub.data.ai.ToolRejectedBeforeExecutionException) { }
        assertEquals(1,store().list().size)
    }
}
