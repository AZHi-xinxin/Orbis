package me.rerere.rikkahub.data.orbis.tabletop

import java.net.SocketException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.*
import org.junit.Test

class TabletopTransportFeedbackTest {
    private class Api:TabletopGameApi {
        override val assistantId="synthetic-assistant"
        override val room="synthetic-room"
        var saved=false
        var attempts=0
        override fun pending(asAi:Boolean)=saved
        override suspend fun roleImage(name:String):String=throw SocketException("private endpoint must not render")
        override suspend fun game(path:String,body:JsonObject?,params:Map<String,String>,asAi:Boolean):JsonObject {
            attempts++;saved=body!=null
            throw SocketException("private endpoint must not render")
        }
    }
    @Test fun lostWriteResponseRemainsUnknownWithoutToolRetry()=runTest {
        val api=Api()
        val answer=tabletopTools(api,true){}.single{it.name=="script_say"}.execute(buildJsonObject {put("channel","public");put("text","synthetic")})
        val result=Json.parseToJsonElement((answer.single() as UIMessagePart.Text).text).jsonObject
        assertEquals("outcome_unknown",result["status"]!!.jsonPrimitive.content)
        assertTrue(result["pending_receipt"]!!.jsonPrimitive.boolean)
        assertFalse(result.containsKey("not_executed"))
        assertFalse(result.toString().contains("private endpoint"))
        assertEquals(1,api.attempts)
    }
    @Test fun failedReadsAndImagesDoNotBecomePermissionFailures()=runTest {
        val api=Api();val tools=tabletopTools(api,true){}
        for((name,args) in listOf("script_status" to buildJsonObject {},"script_read" to buildJsonObject {put("kind","image");put("target","visible.png")})) {
            val result=Json.parseToJsonElement((tools.single{it.name==name}.execute(args).single() as UIMessagePart.Text).text).jsonObject
            assertEquals("temporarily_unavailable",result["status"]!!.jsonPrimitive.content)
            assertFalse(result["pending_receipt"]!!.jsonPrimitive.boolean)
            assertFalse(result.toString().contains("private endpoint"))
        }
    }
}
