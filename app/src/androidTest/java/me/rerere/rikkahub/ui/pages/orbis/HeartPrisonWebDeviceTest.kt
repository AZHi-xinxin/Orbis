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

/** Actual packaged WebView and native bridge; only synthetic game state, no account. */
class HeartPrisonWebDeviceTest {
    private val ins=InstrumentationRegistry.getInstrumentation()
    private val scope=MainScope()
    private val calls=CopyOnWriteArrayList<String>()
    private lateinit var web:WebView
    private lateinit var fixture:JsonObject
    @Before fun setup() {
        check(ins is IsolatedGenerationLoopRunner)
        check(ins.targetContext.packageName=="org.orbis.agent.bodylab")
        fixture=Json.parseToJsonElement(ins.context.assets.open("heartprison-fixture.json").bufferedReader().use{it.readText()}).jsonObject
        ins.runOnMainSync {
            web=createTabletopWebView(ins.targetContext,scope,{action,args->
                calls.add(action+":"+args)
                when(action) {
                    "api"->when(args["path"]?.jsonPrimitive?.content) {
                        "catalog"->buildJsonObject {put("rooms",buildJsonArray {add(buildJsonObject {put("id",fixture["room"]!!);put("stage","playing");put("count",7);put("phase",buildJsonObject {put("name","交流")})})})}
                        "my_room"->buildJsonObject {put("room","")}
                        "observe"->if(args["query"]?.jsonObject?.get("kind")?.jsonPrimitive?.content=="status")fixture["status"]!!.jsonObject else fixture["events"]!!.jsonObject
                        else->buildJsonObject {}
                    }
                    else->buildJsonObject {}
                }
            },{error("renderer gone")},gameKind="heartprison")
            val density=web.resources.displayMetrics.density
            web.measure(android.view.View.MeasureSpec.makeMeasureSpec((390*density).toInt(),android.view.View.MeasureSpec.EXACTLY),android.view.View.MeasureSpec.makeMeasureSpec((844*density).toInt(),android.view.View.MeasureSpec.EXACTLY))
            web.layout(0,0,(390*density).toInt(),(844*density).toInt())
        }
        waitFor("[...document.querySelectorAll('button')].some(b=>b.textContent==='全知观战')")
        js("[...document.querySelectorAll('button')].find(b=>b.textContent==='全知观战').click();void 0")
        waitFor("document.querySelectorAll('.seat[data-person]').length===7")
    }
    @After fun cleanup(){ins.runOnMainSync{scope.cancel();web.stopLoading();web.destroy()}}
    private fun js(code:String):String {
        val latch=CountDownLatch(1);var result=""
        ins.runOnMainSync{web.evaluateJavascript(code){result=it;latch.countDown()}}
        assertTrue(latch.await(5,TimeUnit.SECONDS));return result
    }
    private fun waitFor(code:String){val end=System.nanoTime()+TimeUnit.SECONDS.toNanos(25);while(System.nanoTime()<end){if(js(code)=="true")return;Thread.sleep(80)};fail(code)}
    @Test fun pureSpectatorSeesEveryIdentityWithoutJoiningOrSending() {
        assertEquals("1",js("document.querySelectorAll('.seat .hood').length"))
        assertEquals("false",js("document.documentElement.scrollWidth>innerWidth"))
        assertFalse(calls.any{it.startsWith("connect:")||it.contains("script_pass")})
        ins.runOnMainSync { assertTrue(web.settings.blockNetworkLoads) }
        js("document.querySelector('.seat[data-person]').click();void 0")
        waitFor("document.querySelector('dialog[open]')?.textContent.includes('当前资料')")
    }
    @Test fun privateVisitsAndRepliesStayGroupedAcrossHistoryAndPublicSwitch() {
        js("document.querySelector('#nav [data-tab=chat]').click();void 0")
        waitFor("document.querySelectorAll('.speech.reply').length===1")
        assertEquals("true",js("document.querySelector('.thread').textContent.includes('这一轮的免费回复')"))
        js("let s=document.getElementById('round-filter');s.value='1:1';s.dispatchEvent(new Event('change',{bubbles:true}));void 0")
        waitFor("document.querySelector('.thread')?.textContent.includes('上一轮的免费回复')")
        js("document.querySelector('[data-channel=public]').click();document.querySelector('[data-channel=private]').click();void 0")
        assertEquals("\"1:1\"",js("document.getElementById('round-filter').value"))
        assertEquals("true",js("document.querySelector('.thread').textContent.includes('上一轮的私聊')"))
        assertFalse(calls.any{it.contains("script_pass")})
    }
    @Test fun guideAndSealedInformationRemainReadableDuringSpectating() {
        js("document.querySelector('[data-box]').click();void 0")
        waitFor("document.querySelector('dialog[open]')?.textContent.includes('人类主持资料')")
        assertEquals("true",js("document.querySelector('dialog').textContent.includes('历次黑盒输入')"))
        js("document.querySelector('dialog .close').click();document.getElementById('help').click();void 0")
        waitFor("document.querySelector('dialog[open]')?.textContent.includes('为了游戏公平性请不要给AI场外提示')")
    }
}
