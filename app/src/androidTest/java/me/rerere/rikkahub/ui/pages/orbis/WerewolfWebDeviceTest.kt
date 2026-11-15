package me.rerere.rikkahub.ui.pages.orbis

import android.webkit.WebView
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.*
import me.rerere.rikkahub.data.ai.IsolatedGenerationLoopRunner
import org.junit.*
import org.junit.Assert.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.CopyOnWriteArrayList

/** Production packaged HTML + actual Android WebView bridge, synthetic host only. */
class WerewolfWebDeviceTest {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val scope=MainScope();private val calls=CopyOnWriteArrayList<String>()
    private lateinit var web:WebView
    private var sent=false
    private var night=false
    private fun state()=buildJsonObject {
        put("id","a".repeat(32));put("stage","playing");put("day",1);put("night",false)
        put("phase_version",if(sent)2 else 1);put("self","p0");put("my_role_name","村民")
        put("phase",buildJsonObject{put("kind","speech");put("name","顺位发言")})
        put("turn",buildJsonObject{put("kind","speech");put("actor",if(sent)"p1" else "p0")})
        put("allowed_actions",buildJsonArray{if(!sent){add("say");add("pass")}});put("can_speak",!sent)
        if(night){put("night",true);put("allowed_actions",JsonArray(emptyList()));put("can_speak",false);put("turn",buildJsonObject{});put("phase",buildJsonObject{put("kind","night")})}
        put("actors",buildJsonArray{repeat(10){i->add(buildJsonObject{put("id","p$i");put("seat",i+1);put("name","玩家${i+1}");put("kind",if(i%2==0)"human" else "ai");put("alive",true)})}})
    }
    @Before fun setup() {
        check(instrumentation is IsolatedGenerationLoopRunner)
        check(instrumentation.targetContext.packageName=="org.orbis.agent.bodylab")
        instrumentation.runOnMainSync {
            web=createTabletopWebView(instrumentation.targetContext,scope,{action,args->
                calls.add(action+":"+args)
                when(action) {
                    "asr"->buildJsonObject{put("text","识别结果留在草稿")}
                    "connect"->buildJsonObject{put("room","a".repeat(32))}
                    "api"->when(args["path"]?.jsonPrimitive?.content) {
                        "catalog"->buildJsonObject{put("rules","十人局，由系统主持。");put("rooms",JsonArray(emptyList()))}
                        "room"->buildJsonObject{put("room","a".repeat(32))}
                        "script_status"->state()
                        "script_read"->buildJsonObject{put("items",buildJsonArray{add(buildJsonObject{put("seq",1);put("text","系统正在主持");put("public",true)})})}
                        "script_say"->{sent=true;buildJsonObject{put("ok",true)}}
                        else->buildJsonObject{}
                    }
                    else->buildJsonObject{}
                }
            },{error("renderer gone")},gameKind="werewolf")
            val density=web.resources.displayMetrics.density;val width=(390*density).toInt();val height=(844*density).toInt()
            web.measure(android.view.View.MeasureSpec.makeMeasureSpec(width,android.view.View.MeasureSpec.EXACTLY),android.view.View.MeasureSpec.makeMeasureSpec(height,android.view.View.MeasureSpec.EXACTLY));web.layout(0,0,width,height)
        }
        waitFor("document.body.textContent.includes('今夜，一起入席')")
    }
    @After fun cleanup(){instrumentation.runOnMainSync{scope.cancel();web.stopLoading();web.destroy()}}
    private fun js(text:String):String {
        val done=CountDownLatch(1);var result=""
        instrumentation.runOnMainSync{web.evaluateJavascript(text){result=it;done.countDown()}}
        assertTrue(done.await(5,TimeUnit.SECONDS));return result
    }
    private fun waitFor(condition:String){val end=System.nanoTime()+TimeUnit.SECONDS.toNanos(25);while(System.nanoTime()<end){if(js(condition)=="true")return;Thread.sleep(80)};fail("Condition: $condition")}
    private fun click(label:String){js("[...document.querySelectorAll('button')].find(x=>x.textContent=="+Json.encodeToString(label)+").click();void 0")}
    @Test fun nativeEntryLoadsAndCreatesTenSeatsWithoutAnExternalDm() {
        click("人机共玩");waitFor("document.querySelectorAll('.ww-seat').length===10")
        assertEquals("true",js("document.querySelector('.ww-feed').getBoundingClientRect().height>150"))
        assertTrue(calls.any{it.contains("connect")});assertFalse(calls.any{it.contains("script_say")})
        click("游戏规则");waitFor("document.querySelector('dialog[open]')!==null")
        assertEquals("true",js("document.querySelector('dialog').textContent.includes('系统主持')"))
    }
    @Test fun asrOnlyFillsDraftThenHumanExplicitSendEndsTurn() {
        click("人机共玩");waitFor("document.querySelector('textarea')!==null")
        click("语音转文字");waitFor("document.querySelector('textarea').value==='识别结果留在草稿'")
        assertFalse(sent);assertFalse(calls.any{it.contains("script_say")})
        click("发送并结束发言");waitFor("document.querySelector('textarea').value===''")
        assertTrue(sent);assertEquals(1,calls.count{it.contains("script_say")})
        assertEquals("true",js("[...document.querySelectorAll('button')].find(x=>x.textContent==='等待我的回合').disabled"))
    }
    @Test fun inactiveNightHasNoRolePanelOrChatBox() {
        night=true;click("人机共玩")
        waitFor("document.body.textContent.includes('寂静的夜晚…')")
        assertEquals("0",js("document.querySelectorAll('.ww-night-card,input,textarea').length"))
        assertEquals("true",js("[...document.querySelectorAll('button')].some(x=>x.textContent==='游戏规则')"))
    }
}
