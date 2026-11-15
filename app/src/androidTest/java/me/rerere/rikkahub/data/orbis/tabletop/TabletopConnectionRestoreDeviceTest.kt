package me.rerere.rikkahub.data.orbis.tabletop

import android.content.Context
import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

/** Local preferences and a saved ending only: no live room or network calls. */
class TabletopConnectionRestoreDeviceTest {
    private fun fixture(block:suspend (TabletopConversationHost,android.content.SharedPreferences)->Unit)=runBlocking {
        val base=InstrumentationRegistry.getInstrumentation().targetContext
        check(base.packageName=="org.orbis.agent.bodylab")
        val name="tabletop-restore-test-"+UUID.randomUUID()
        val context=object:ContextWrapper(base) {
            override fun getApplicationContext():Context=this
            override fun getSharedPreferences(unused:String,mode:Int)=base.getSharedPreferences(name,mode)
        }
        val constructor=TabletopConversationHost::class.java.getDeclaredConstructor(Context::class.java)
        constructor.isAccessible=true
        val prefs=context.getSharedPreferences("unused",0)
        try { block(constructor.newInstance(context),prefs) }
        finally { prefs.edit().clear().commit();base.deleteSharedPreferences(name) }
    }
    private fun ending()=Json.parseToJsonElement("""{"game_kind":"werewolf","id":"room","room":"room","mode":"ai_only","stage":"ended","winner":"good","self":"assistant","roles":[]}""").jsonObject

    @Test fun savedRoomKeepsToolsAfterProcessLossAndUsesCurrentOwnedChat()=fixture { host,prefs ->
        prefs.edit().putString("active:assistant","room").putString("assistant:room","old-chat")
            .putString("game:assistant:room","werewolf").putBoolean("automatic:assistant:room",false)
            .putString("ending:assistant:room",ending().toString()).commit()
        assertNull(host.current("assistant"))
        assertTrue(host.tools("other-assistant",false).isEmpty())
        host.useConversation("assistant","current-chat")
        val status=host.tools("assistant",false).single { it.name=="werewolf_status" }
        val reply=Json.parseToJsonElement((status.execute(buildJsonObject {}).single() as UIMessagePart.Text).text).jsonObject
        assertEquals("room",reply["id"]?.jsonPrimitive?.content)
        assertEquals("good",reply["winner"]?.jsonPrimitive?.content)
        assertEquals("current-chat",host.current("assistant")?.conversationId)
        assertFalse(host.current("assistant")!!.automatic)
        assertFalse(host.state("assistant").value.enabled)
        host.clear("assistant")
        assertTrue(host.tools("assistant",false).isEmpty())
    }

    @Test fun expiredPagePresenceDoesNotRemoveToolsOrUnpauseAutomaticActions()=fixture { host,_ ->
        host.bind(WerewolfEndingApi("assistant","room",ending()),"chat","合成聊天")
        host.setAutomatic("assistant",false)
        val field=TabletopConversationHost::class.java.getDeclaredField("bindings").apply { isAccessible=true }
        @Suppress("UNCHECKED_CAST")
        val bindings=field.get(host) as MutableMap<String,TabletopConversationBinding>
        bindings["assistant"]=bindings.getValue("assistant").copy(awaySince=System.currentTimeMillis()-2*60*60*1000L)
        assertNull(host.current("assistant"))
        val reply=host.tools("assistant",false).single { it.name=="werewolf_status" }.execute(buildJsonObject {})
        assertTrue((reply.single() as UIMessagePart.Text).text.contains("good"))
        assertEquals("room",host.current("assistant")?.room)
        assertFalse(host.current("assistant")!!.automatic)
    }
    @Test fun drawGuessReconnectReachesRealHostProxyAndKeepsHumanPause()=fixture {host,_ ->
        var restores=0;var writes=0
        val api=object:TabletopGameApi {
            override val assistantId="draw-assistant";override val room="draw-room";override val gameKind="drawguess"
            override fun pending(asAi:Boolean)=true
            override suspend fun roleImage(name:String)="file:///unused.png"
            override suspend fun restoreConnection(){restores++}
            override suspend fun game(path:String,body:JsonObject?,params:Map<String,String>,asAi:Boolean):JsonObject {
                if(body!=null)writes++
                check(asAi && path=="script_status")
                return buildJsonObject {put("id",room);put("stage","playing");put("phase_version",4)}
            }
        }
        host.bind(api,"same-chat","合成聊天");host.setAutomatic(api.assistantId,false)
        val tool=host.tools(api.assistantId,true).single {it.name=="drawguess_reconnect"}
        val result=Json.parseToJsonElement((tool.execute(buildJsonObject {}).first() as UIMessagePart.Text).text).jsonObject
        assertTrue(result["reconnected"]!!.jsonPrimitive.boolean);assertEquals(1,restores);assertEquals(0,writes)
        assertEquals("same-chat",host.current(api.assistantId)!!.conversationId);assertFalse(host.current(api.assistantId)!!.automatic);assertFalse(host.state(api.assistantId).value.enabled)
        host.clear(api.assistantId)
        val stale=Json.parseToJsonElement((tool.execute(buildJsonObject {}).first() as UIMessagePart.Text).text).jsonObject
        assertFalse(stale["ok"]!!.jsonPrimitive.boolean);assertEquals(1,restores)
    }

}
