package me.rerere.rikkahub.ui.pages.orbis

import android.app.Application
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import me.rerere.rikkahub.data.ai.IsolatedGenerationLoopRunner
import me.rerere.rikkahub.data.orbis.OrbisGameStorage
import me.rerere.rikkahub.data.orbis.OrbisMiniGameRepository
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Production sandbox + synthetic in-memory game repository. No files, models or user chat. */
@RunWith(AndroidJUnit4::class)
class OrbisMiniGameWebViewTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val views = mutableListOf<WebView>()
    private val gates = mutableListOf<OrbisMiniGameResultGate>()

    @Before fun requireIsolatedApplication() {
        check(instrumentation is IsolatedGenerationLoopRunner)
        assertEquals(Application::class.java, instrumentation.targetContext.applicationContext.javaClass)
    }

    @After fun disposeFixtures() {
        gates.forEach { it.close() }
        instrumentation.runOnMainSync {
            views.forEach { it.removeJavascriptInterface("OrbisGame"); it.stopLoading(); it.destroy() }
        }
    }

    private fun createView(html: String, gate: OrbisMiniGameResultGate, policy: OrbisMiniGamePolicy = OrbisMiniGamePolicy()): WebView {
        val ready = CountDownLatch(1)
        lateinit var view: WebView
        instrumentation.runOnMainSync {
            view = createOrbisMiniGameWebView(instrumentation.targetContext, policy, html, gate,
                onRendererFailure = { ready.countDown() }, onLoaded = { ready.countDown() })
            views += view
            gates += gate
        }
        assertTrue("Synthetic game did not load", ready.await(15, TimeUnit.SECONDS))
        return view
    }

    private fun evaluate(view: WebView, script: String): String {
        val ready = CountDownLatch(1)
        var result = ""
        instrumentation.runOnMainSync { view.evaluateJavascript(script) { result = it; ready.countDown() } }
        assertTrue("Synthetic JavaScript timed out", ready.await(5, TimeUnit.SECONDS))
        return result
    }

    @Test fun selfContainedTicTacToePlaysAndReturnsOneBoundHostTimedRecord() {
        var clock = 1000L
        var sequence = 0
        val storage = MemoryStorage()
        val repository = OrbisMiniGameRepository(storage, { clock }, { "fixture-${++sequence}" })
        val game = repository.install("fixture-ai", "合成 AI", "合成井字棋", "测试用", TIC_TAC_TOE)
        assertTrue(repository.readSnapshot().sessions.isEmpty()) // install never runs the game
        val session = repository.start(game.id)
        val gate = OrbisMiniGameResultGate { repository.finish(session.id, it.result, it.moveCount) }
        val view = createView(game.html, gate)
        assertEquals("9", evaluate(view, "document.querySelectorAll('button').length"))
        assertEquals("\"undefined\"", evaluate(view, "typeof window.Android"))
        assertEquals("\"undefined\"", evaluate(view, "typeof OrbisGame.chooseMove"))
        clock = 9000L
        evaluate(view, "[0,3,1,4,2].forEach(i=>document.getElementById('c'+i).click()); void 0")
        assertEquals("true", evaluate(view, "window.receipt.ok"))
        val record = repository.readSnapshot().sessions.single()
        assertEquals(session.id, record.id)
        assertEquals(game.sha256, record.gameSha256)
        assertEquals("win", record.result)
        assertEquals(5, record.moveCount)
        assertEquals(8000L, record.finishedAt!! - record.startedAt)
        assertEquals("true", evaluate(view, "JSON.parse(OrbisGame.finish(JSON.stringify({result:'win',move_count:5}))).ok"))
        assertEquals(1, repository.readSnapshot().sessions.size)
        assertEquals(record, OrbisMiniGameRepository(storage).readSnapshot().sessions.single())
        assertEquals("false", evaluate(view, "JSON.parse(OrbisGame.finish(JSON.stringify({result:'loss',move_count:5}))).ok"))
        assertEquals(record, repository.readSnapshot().sessions.single())
    }

    @Test fun sandboxBlocksNavigationNetworkPrivateResourcesAndForeignSessionSelectors() {
        var writes = 0
        val policy = OrbisMiniGamePolicy()
        val gate = OrbisMiniGameResultGate { writes++ }
        val view = createView(TIC_TAC_TOE, gate, policy)
        val client = OrbisMiniGameWebViewClient(policy, TIC_TAC_TOE, {})
        listOf("file:///private/fixture", "content://private/fixture", "https://example.invalid/", "http://127.0.0.1:8081/",
            policy.pageUrl + "?path=private").forEach { target ->
            assertEquals(403, client.shouldInterceptRequest(view, Request(target, true)).statusCode)
            instrumentation.runOnMainSync { assertTrue(client.shouldOverrideUrlLoading(view, Request(target, true))) }
        }
        assertEquals(403, client.shouldInterceptRequest(view, Request(policy.pageUrl, false)).statusCode)
        assertTrue(client.shouldInterceptRequest(view, Request(policy.pageUrl, true)).responseHeaders["Content-Security-Policy"]!!.contains("connect-src 'none'"))
        assertEquals("off", client.shouldInterceptRequest(view, Request(policy.pageUrl, true)).responseHeaders["X-DNS-Prefetch-Control"])
        instrumentation.runOnMainSync {
            assertFalse(view.settings.allowFileAccess)
            assertFalse(view.settings.allowContentAccess)
            assertTrue(view.settings.blockNetworkLoads)
            assertFalse(view.settings.domStorageEnabled)
        }
        assertEquals("false", evaluate(view, "JSON.parse(OrbisGame.finish(JSON.stringify({result:'win',move_count:5,session_id:'foreign'}))).ok"))
        assertEquals(0, writes)
        evaluate(view, "window.network='waiting'; fetch('https://example.invalid/no-network').then(()=>window.network='unexpected').catch(()=>window.network='blocked'); void 0")
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (evaluate(view, "window.network") == "\"waiting\"" && System.nanoTime() < deadline) Thread.sleep(20)
        assertEquals("\"blocked\"", evaluate(view, "window.network"))
        gate.close()
        assertEquals("false", evaluate(view, "JSON.parse(OrbisGame.finish(JSON.stringify({result:'win',move_count:5}))).ok"))
        assertEquals(0, writes)
    }

    @Test fun rtcAndNewRealmBypassesRemainUnavailableAfterDocumentReplacement() {
        val view = createView(TIC_TAC_TOE, OrbisMiniGameResultGate {})
        assertEquals("\"undefined\"", evaluate(view, "typeof RTCPeerConnection"))
        assertEquals("false", evaluate(view, "Object.getOwnPropertyDescriptor(window,'RTCPeerConnection').configurable"))
        assertEquals("\"undefined\"", evaluate(view, "window.RTCPeerConnection=function(){}; typeof RTCPeerConnection"))
        assertEquals("\"undefined\"", evaluate(view, "typeof Worker"))
        assertEquals("null", evaluate(view, "window.open('about:blank')"))
        // No STUN/server is ever contacted: only the presence of constructors is inspected.
        assertEquals("false", evaluate(view, "(function(){let f=document.createElement('iframe');document.body.appendChild(f);try{return typeof f.contentWindow.RTCPeerConnection==='function';}catch(e){return false;}finally{f.remove();}})()"))
        evaluate(view, "document.open();document.write('<html><body>replacement</body></html>');document.close();void 0")
        assertEquals("\"undefined\"", evaluate(view, "typeof RTCPeerConnection"))
        assertEquals("\"undefined\"", evaluate(view, "typeof WebTransport"))
    }

    @Test fun capturedSourceDoesNotChangeWhenAnInstalledGameIsUpdated() {
        var sequence = 0
        val repository = OrbisMiniGameRepository(MemoryStorage(), { 1000L }, { "version-${++sequence}" })
        val original = repository.install("fixture-ai", "合成 AI", "版本测试", "", TIC_TAC_TOE)
        val session = repository.start(original.id)
        val view = createView(original.html, OrbisMiniGameResultGate {
            repository.finish(session.id, it.result, it.moveCount)
        })
        val updated = repository.install("fixture-ai", "合成 AI", "版本测试", "", "<html><body>new version</body></html>",
            original.id, original.sha256)
        assertNotEquals(original.sha256, updated.sha256)
        assertEquals("9", evaluate(view, "document.querySelectorAll('button').length"))
        evaluate(view, "[0,3,1,4,2].forEach(i=>document.getElementById('c'+i).click()); void 0")
        assertEquals(original.sha256, repository.readSnapshot().sessions.single().gameSha256)
        assertEquals("win", repository.readSnapshot().sessions.single().result)
    }

    private class MemoryStorage : OrbisGameStorage {
        private var text: String? = null
        override fun read() = text
        override fun write(value: String) { text = value }
    }

    private class Request(private val target: String, private val main: Boolean) : WebResourceRequest {
        override fun getUrl(): Uri = Uri.parse(target)
        override fun isForMainFrame() = main
        override fun isRedirect() = false
        override fun hasGesture() = true
        override fun getMethod() = "GET"
        override fun getRequestHeaders(): Map<String, String> = emptyMap()
    }

    companion object {
        private val TIC_TAC_TOE = """
            <!doctype html><html><head><meta name="viewport" content="width=device-width, initial-scale=1">
            <style>button{width:30%;height:64px;touch-action:manipulation}</style></head><body>
            <div id="board"></div><p id="status">play</p><script>
            const cells=Array(9).fill('');let moves=0,done=false;
            const lines=[[0,1,2],[3,4,5],[6,7,8],[0,3,6],[1,4,7],[2,5,8],[0,4,8],[2,4,6]];
            for(let i=0;i<9;i++){const b=document.createElement('button');b.id='c'+i;b.textContent='·';
              b.onclick=()=>{if(done||cells[i])return;cells[i]=moves%2?'O':'X';b.textContent=cells[i];moves++;
                if(lines.some(l=>l.every(c=>cells[c]===cells[i]))){done=true;window.receipt=JSON.parse(OrbisGame.finish(JSON.stringify({result:'win',move_count:moves})));document.getElementById('status').textContent='done';}};
              document.getElementById('board').appendChild(b);}
            </script></body></html>
        """.trimIndent()
    }
}
