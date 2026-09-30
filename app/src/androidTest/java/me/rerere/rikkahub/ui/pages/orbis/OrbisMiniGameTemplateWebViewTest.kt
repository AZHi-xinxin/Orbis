package me.rerere.rikkahub.ui.pages.orbis

import android.app.Application
import android.webkit.WebView
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import me.rerere.rikkahub.data.ai.IsolatedGenerationLoopRunner
import me.rerere.rikkahub.data.orbis.OrbisGameStorage
import me.rerere.rikkahub.data.orbis.OrbisMiniGameRepository
import me.rerere.rikkahub.data.orbis.OrbisMiniGameTemplates
import me.rerere.rikkahub.testutil.createShellComposeRule
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** Production sandbox, production template and in-memory sessions. No user library, models or network. */
@RunWith(AndroidJUnit4::class)
class OrbisMiniGameTemplateWebViewTest {
    @get:Rule val compose = createShellComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val width = mutableStateOf(320)
    private val height = mutableStateOf(520)
    private val ready = AtomicBoolean(false)
    private val rendererFailure = AtomicReference<AssertionError?>(null)
    private val saves = AtomicInteger()
    private val clock = AtomicLong(1000L)
    private var sequence = 0
    private val repository = OrbisMiniGameRepository(MemoryStorage(), clock::get, { "template-${++sequence}" })
    private lateinit var view: WebView
    private lateinit var gate: OrbisMiniGameResultGate
    private lateinit var sessionId: String

    @Before fun requireIsolatedApplication() {
        check(instrumentation is IsolatedGenerationLoopRunner)
        assertEquals(Application::class.java, instrumentation.targetContext.applicationContext.javaClass)
    }

    @After fun closeOnlyFixtureGate() {
        if (::gate.isInitialized) gate.close()
        // AndroidView's onRelease destroys only the WebView owned by this test activity.
        assertRendererAlive()
    }

    private fun mount(failFirstSave: Boolean = false) {
        val game = repository.install("synthetic-ai", "合成 AI", "井字棋模板", "本地程序对手", OrbisMiniGameTemplates.html)
        assertTrue(repository.readSnapshot().sessions.isEmpty())
        sessionId = repository.start(game.id).id
        gate = OrbisMiniGameResultGate {
            if (saves.incrementAndGet() == 1 && failFirstSave) error("synthetic failure before saving")
            repository.finish(sessionId, it.result, it.moveCount)
        }
        compose.setContent {
            AndroidView(
                modifier = Modifier.requiredSize(width.value.dp, height.value.dp),
                factory = { context -> createOrbisMiniGameWebView(context, OrbisMiniGamePolicy(),
                    OrbisMiniGameTemplates.html, gate,
                    onRendererFailure = {
                        gate.close()
                        rendererFailure.compareAndSet(null, AssertionError("Synthetic template renderer failed"))
                        ready.set(true) // Wake the test thread, which reports the failure without crashing the UI thread.
                    },
                    onLoaded = { ready.set(true) },
                ).also { view = it } },
                onRelease = { owned ->
                    owned.removeJavascriptInterface("OrbisGame")
                    owned.stopLoading()
                    owned.destroy()
                },
            )
        }
        compose.waitUntil(15_000) { ready.get() }
        assertRendererAlive()
        compose.waitUntil(5_000) { evaluate("document.getElementById('game-board').style.width.length > 0") == "true" }
        assertEquals(0, saves.get())
        assertNull(repository.readSnapshot().sessions.single().finishedAt)
        assertEquals("9", evaluate("document.querySelectorAll('#game-board button').length"))
    }

    private fun evaluate(script: String): String {
        assertRendererAlive()
        val completed = CountDownLatch(1)
        var output = ""
        instrumentation.runOnMainSync { view.evaluateJavascript(script) { output = it; completed.countDown() } }
        val completedInTime = completed.await(5, TimeUnit.SECONDS)
        assertRendererAlive()
        assertTrue("Template JS timed out", completedInTime)
        return output
    }

    private fun assertRendererAlive() {
        rendererFailure.get()?.let { throw it }
    }

    private fun play(vararg humanMoves: Int) {
        clock.set(9000L)
        humanMoves.forEach { index ->
            assertEquals("false", evaluate("document.getElementById('cell-$index').disabled"))
            evaluate("document.getElementById('cell-$index').click(); void 0")
        }
    }

    private fun assertRecorded(result: String, moves: Int) {
        assertEquals("\"true\"", evaluate("document.getElementById('result-receipt').dataset.saved"))
        assertEquals("\"$result\"", evaluate("document.getElementById('result-receipt').dataset.result"))
        val record = repository.readSnapshot().sessions.single()
        assertEquals(sessionId, record.id)
        assertEquals(result, record.result)
        assertEquals(moves, record.moveCount)
        assertEquals(8000L, record.finishedAt!! - record.startedAt)
        assertEquals("true", evaluate("Array.from(document.querySelectorAll('#game-board button')).every(b=>b.disabled)"))
        assertEquals("true", evaluate("document.getElementById('retry-result').hidden"))
    }

    @Test fun humanWinUsesXPerspectiveAndReplayCannotCreateAnotherHostSession() {
        mount()
        play(0, 7, 6, 8)
        assertRecorded("win", 7)
        val record = repository.readSnapshot().sessions.single()
        evaluate("document.getElementById('replay-guide').click(); document.getElementById('cell-1').click(); void 0")
        assertEquals("false", evaluate("document.getElementById('replay-note').hidden"))
        assertEquals(record, repository.readSnapshot().sessions.single())
        assertEquals(1, saves.get())
    }

    @Test fun localOpponentWinReturnsHumanLoss() {
        mount()
        play(0, 1, 3)
        assertRecorded("loss", 6)
        assertEquals(1, saves.get())
    }

    @Test fun fullBoardWithoutWinnerReturnsDrawAndBothSidesMoveCount() {
        mount()
        play(0, 1, 6, 5, 7)
        assertRecorded("draw", 9)
        assertEquals(1, saves.get())
    }

    @Test fun rejectedReceiptKeepsTerminalBoardAndRetriesOnlyTheIdenticalResult() {
        mount(failFirstSave = true)
        play(0, 7, 6, 8)
        assertEquals("\"false\"", evaluate("document.getElementById('result-receipt').dataset.saved"))
        assertEquals("false", evaluate("document.getElementById('retry-result').hidden"))
        assertEquals("true", evaluate("Array.from(document.querySelectorAll('#game-board button')).every(b=>b.disabled)"))
        assertNull(repository.readSnapshot().sessions.single().finishedAt)
        val terminalBoard = evaluate("document.getElementById('game-board').textContent")
        evaluate("document.getElementById('retry-result').click(); void 0")
        assertRecorded("win", 7)
        assertEquals(terminalBoard, evaluate("document.getElementById('game-board').textContent"))
        evaluate("document.getElementById('retry-result').click(); void 0")
        assertEquals(2, saves.get())
        assertEquals(1, repository.readSnapshot().sessions.size)
    }

    @Test fun narrowShortResizedViewportAndLargeTextKeepTitleVisibleAndFooterScrollable() {
        mount()
        val before = layout()
        assertTrue(before.getDouble("titleTop") >= 0)
        compose.runOnIdle {
            width.value = 210
            height.value = 220
            view.settings.textZoom = 220
        }
        compose.waitUntil(5_000) {
            val current = layout()
            current.getDouble("viewportWidth") < before.getDouble("viewportWidth") &&
                current.getDouble("viewportHeight") < before.getDouble("viewportHeight") &&
                current.getDouble("titleHeight") > before.getDouble("titleHeight") &&
                current.getDouble("boardRight") <= current.getDouble("viewportWidth") + 1
        }
        val narrow = stableLayout()
        assertTrue(narrow.getDouble("titleTop") >= 0)
        assertTrue(narrow.getDouble("titleBottom") <= narrow.getDouble("viewportHeight"))
        assertTrue(narrow.getDouble("boardLeft") >= 0)
        assertTrue(narrow.getDouble("boardWidth") > 0)
        assertTrue(narrow.getDouble("documentWidth") <= narrow.getDouble("viewportWidth") + 1)
        assertTrue(narrow.getDouble("documentHeight") > narrow.getDouble("viewportHeight"))
        compose.waitUntil(5_000) {
            // A late font/ResizeObserver layout must not leave the scroll target at an old height.
            evaluate("window.scrollTo(0,document.documentElement.scrollHeight); void 0")
            val current = layout()
            current.getDouble("footerBottom") <= current.getDouble("viewportHeight") + 1
        }
        assertEquals(0, saves.get())
    }

    /** Sample on renderer frames, not two unrelated instrumentation polls during a resize. */
    private fun stableLayout(): JSONObject {
        evaluate("""
            window.__orbisTemplateLayoutProbe = null;
            (function(){
              let previous = null, stableFrames = 0;
              function sample(){
                const current = $layoutExpression;
                const stable = previous && Object.keys(current).every(function(key){
                  return Math.abs(current[key] - previous[key]) <= 0.25;
                });
                stableFrames = stable ? stableFrames + 1 : 0;
                if (stableFrames >= 3) { window.__orbisTemplateLayoutProbe = current; return; }
                previous = current;
                requestAnimationFrame(sample);
              }
              requestAnimationFrame(sample);
            })(); void 0
        """.trimIndent())
        var snapshot: JSONObject? = null
        compose.waitUntil(5_000) {
            val result = evaluate("window.__orbisTemplateLayoutProbe")
            if (result == "null") false else {
                snapshot = JSONObject(result)
                true
            }
        }
        return requireNotNull(snapshot)
    }

    private fun layout(): JSONObject = JSONObject(evaluate(layoutExpression))

    private val layoutExpression = """
        (function(){
          const title=document.getElementById('game-title').getBoundingClientRect();
          const board=document.getElementById('game-board').getBoundingClientRect();
          const footer=document.getElementById('game-footer').getBoundingClientRect();
          return {titleTop:title.top,titleBottom:title.bottom,titleHeight:title.height,boardLeft:board.left,boardRight:board.right,
            boardWidth:board.width,footerBottom:footer.bottom,viewportWidth:innerWidth,viewportHeight:innerHeight,
            documentWidth:document.documentElement.scrollWidth,documentHeight:document.documentElement.scrollHeight};
        })()
    """.trimIndent()

    private class MemoryStorage : OrbisGameStorage {
        private var content: String? = null
        override fun read() = content
        override fun write(value: String) { content = value }
    }
}
