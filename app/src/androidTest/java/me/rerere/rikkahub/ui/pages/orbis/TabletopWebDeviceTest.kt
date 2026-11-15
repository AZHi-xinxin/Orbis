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

/** Exercises the production WebView factory with an in-process host, no account or network. */
class TabletopWebDeviceTest {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val scope=MainScope();private val calls=CopyOnWriteArrayList<String>()
    private lateinit var web:WebView
    @Before fun setup() {
        check(instrumentation is IsolatedGenerationLoopRunner)
        check(instrumentation.targetContext.packageName=="org.orbis.agent.bodylab")
        instrumentation.runOnMainSync { web=createTabletopWebView(instrumentation.targetContext,scope,{action,args->
            calls.add(action+":"+args.toString())
            if(action=="api" && args["path"]?.jsonPrimitive?.content=="catalog")buildJsonObject { put("scripts",JsonArray(emptyList()));put("rooms",JsonArray(emptyList()));put("day","本地测试");put("daily",JsonArray(emptyList())) } else buildJsonObject {}
        },{error("renderer gone")}) }
        waitFor("document.querySelector('.book-grid')!==null")
    }
    @After fun cleanup() { instrumentation.runOnMainSync { scope.cancel();web.stopLoading();web.destroy() } }
    private fun js(text:String):String {
        val done=CountDownLatch(1);var value=""
        instrumentation.runOnMainSync { web.evaluateJavascript(text){value=it;done.countDown()} }
        assertTrue(done.await(5,TimeUnit.SECONDS));return value
    }
    private fun waitFor(condition:String) {
        val until=System.nanoTime()+TimeUnit.SECONDS.toNanos(30)
        while(System.nanoTime()<until){if(js(condition)=="true")return;Thread.sleep(80)}
        fail("WebView condition did not become true: $condition")
    }
    @Test fun packagedUiLoadsAndOnlyExplicitHostCallsRun() {
        assertTrue(calls.any { it.startsWith("api:") && it.contains("catalog") })
        assertFalse(calls.any { it.contains("upload") || it.contains("prepare") || it.contains("script_say") })
        assertEquals("\"undefined\"",js("typeof Android"))
        instrumentation.runOnMainSync {
            assertTrue(web.settings.blockNetworkLoads)
            assertFalse(web.settings.allowFileAccess);assertFalse(web.settings.allowContentAccess)
        }
    }
    @Test fun bundledAssetsBypassHttpCacheWithoutErasingLocalDrafts() {
        instrumentation.runOnMainSync { assertEquals(android.webkit.WebSettings.LOAD_NO_CACHE,web.settings.cacheMode) }
        js("localStorage.setItem('drawguess-cache-probe','saved-draft');window.cachePolicy='';fetch('avatar.js').then(r=>window.cachePolicy=r.headers.get('Cache-Control'));void 0")
        waitFor("window.cachePolicy==='no-store'")
        instrumentation.runOnMainSync { web.reload() }
        waitFor("document.querySelector('.book-grid')!==null")
        assertEquals("\"saved-draft\"",js("localStorage.getItem('drawguess-cache-probe')"))
        js("localStorage.removeItem('drawguess-cache-probe');void 0")
    }
    @Test fun opaqueAuthoredFrameCannotReachNativeBridgeOrParent() {
        val before=calls.size
        js("""window.frameResult=null;addEventListener('message',e=>{if(e.data?.kind==='probe')window.frameResult=e.data;});const f=document.createElement('iframe');f.sandbox='allow-scripts';f.srcdoc='<script>let parentAccessible=false;try{parentAccessible=!!parent.OrbisTabletopBridge}catch(e){};parent.postMessage({kind:"probe",bridge:typeof OrbisTabletopBridge,parentAccessible},"*");<\/script>';document.body.append(f);void 0""")
        waitFor("window.frameResult!==null")
        assertEquals("\"undefined\"",js("window.frameResult.bridge"));assertEquals("false",js("window.frameResult.parentAccessible"))
        assertEquals(before,calls.size)
    }
    @Test fun malformedAndOversizedBridgeRequestsDoNotDispatch() {
        val before=calls.size
        js("OrbisTabletopBridge.postMessage('bad');OrbisTabletopBridge.postMessage(JSON.stringify({id:'../../bad',action:'upload'}));OrbisTabletopBridge.postMessage('x'.repeat(70000));void 0")
        Thread.sleep(250);assertEquals(before,calls.size)
    }
    @Test fun viewportAndStoryDialogDoNotCollapseToAStrip() {
        instrumentation.runOnMainSync {
            assertEquals(android.view.ViewGroup.LayoutParams.MATCH_PARENT,web.layoutParams.height)
            val density=web.resources.displayMetrics.density
            val width=(430*density).toInt();val height=(932*density).toInt()
            web.measure(android.view.View.MeasureSpec.makeMeasureSpec(width,android.view.View.MeasureSpec.EXACTLY),android.view.View.MeasureSpec.makeMeasureSpec(height,android.view.View.MeasureSpec.EXACTLY))
            web.layout(0,0,width,height)
        }
        waitFor("innerHeight>600")
        js("document.getElementById('dialog-body').innerHTML='<h2>完整故事</h2><p>'+('原稿公开介绍。'.repeat(150))+'</p>';document.getElementById('dialog').showModal();void 0")
        waitFor("document.getElementById('dialog').getBoundingClientRect().height>250")
        assertEquals("true",js("document.getElementById('dialog').getBoundingClientRect().height<=innerHeight"))
        js("document.getElementById('dialog').close();document.body.classList.add('tabletop-fullscreen');document.getElementById('app').innerHTML='<section class=fs-table></section>';void 0")
        waitFor("document.querySelector('.fs-table').getBoundingClientRect().height>600")
    }
}
