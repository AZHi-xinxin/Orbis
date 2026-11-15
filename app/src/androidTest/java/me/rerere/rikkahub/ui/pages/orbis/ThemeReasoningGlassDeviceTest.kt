package me.rerere.rikkahub.ui.pages.orbis

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.IsolatedGenerationLoopRunner
import me.rerere.rikkahub.data.datastore.DisplaySetting
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.orbis.theme.*
import me.rerere.rikkahub.ui.components.message.*
import me.rerere.rikkahub.ui.context.*
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import kotlin.time.Instant

/** Intentional outline presentation for open details, including partly offscreen chat bounds. */
class ThemeReasoningGlassDeviceTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()

    @Test fun openingLongReasoningKeepsOutlineUntilClosedAndSettled() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        check(instrumentation is IsolatedGenerationLoopRunner && context.packageName.endsWith(".bodylab"))
        val settings=Settings(init=true,providers=emptyList(),displaySetting=DisplaySetting(
            enableMessageGenerationHapticEffect=false,showThinkingContent=true,autoCloseThinking=true))
        var paragraphs by mutableStateOf(6)
        val boundsRef=java.util.concurrent.atomic.AtomicReference(Rect.Zero)
        var mode by mutableStateOf("liquid")
        compose.setContent { MaterialTheme { ThemeMotionProvider(true) {
            val reasoning=remember(paragraphs) { UIMessagePart.Reasoning(
                reasoning=(1..paragraphs).joinToString("\n\n") { "Synthetic reasoning paragraph $it. A long explanation exercises the actual chat layout." },
                createdAt=Instant.parse("2026-11-07T00:00:00Z"),finishedAt=Instant.parse("2026-11-07T00:00:03Z")) }
            val source=rememberHazeState()
            val list=rememberLazyListState()
            CompositionLocalProvider(LocalSettings provides settings,LocalNavController provides Navigator(mutableListOf()),
                LocalBubbleBackdrop provides source) {
                Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                    Box(Modifier.fillMaxSize().hazeSource(source).background(Color(0xFF183249)))
                    LazyColumn(Modifier.fillMaxSize().padding(bottom=50.dp).testTag("glass-chat"),state=list,reverseLayout=true,
                        contentPadding=PaddingValues(horizontal=22.dp,vertical=16.dp)) {
                        item(key="$mode-$paragraphs") {
                            val glass=ThemeBubbleMaterial(type=if(mode=="frosted") "frosted" else "liquid",blur=3f,
                                depth=0f,tintOpacity=.3f,refraction=.9f,displacement=14f,
                                flow=if(mode in listOf("static","no-flow-layer")) 0f else .85f,metallic=if(mode=="no-flow-layer") 0f else .4f,
                                dispersion=if(mode=="no-flow-layer") 0f else .55f,fold=.025f)
                            OrbisThemedBubble(false,ThemeBubble(bg="#EEF7FF",material=glass,maxWidthRatio=1f),
                                Modifier.fillMaxWidth().onGloballyPositioned {boundsRef.set(it.boundsInWindow())}.testTag("reasoning-glass")) {
                                MessagePartsBlock(null,MessageRole.ASSISTANT,null,
                                    listOf(reasoning,UIMessagePart.Text("Synthetic final reply.")),emptyList(),false,
                                    segmentedReply=false,messageKey="glass-reasoning-$mode")
                                Spacer(Modifier.fillMaxWidth().height(60.dp))
                            }
                        }
                    }
                }
            }
        } } }
        val samples=mutableListOf<String>()
        fun capture(label:String):Int {
            val bounds=boundsRef.get()
            val bitmap=instrumentation.uiAutomation.takeScreenshot()
            // Blank padding at the bubble's bottom stays visible in the reverse list.
            val x=(bounds.left+bounds.width*.5f).toInt();val y=(bounds.bottom-60).toInt()
            val value=android.graphics.Color.red(bitmap.getPixel(x,y))
            samples+="$label,$value,${bounds.width},${bounds.height}"
            if(label.endsWith("-final")) File(context.getExternalFilesDir(null),"reasoning-glass-$label.png").outputStream().use {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)
            }
            bitmap.recycle();return value
        }
        val failures=mutableListOf<String>()
        for (length in listOf(6,18,80)) for (variant in listOf("liquid","static","no-flow-layer","frosted")) {
            compose.mainClock.autoAdvance=true
            compose.runOnIdle {mode=variant;paragraphs=length}
            compose.waitForIdle();compose.mainClock.advanceTimeBy(1000);compose.waitForIdle();Thread.sleep(150)
            if(capture("$variant-$length-collapsed")<45) failures+="$variant/$length initial glass missing"
            val toggle=compose.onNode(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription,"已折叠"))
                .fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
            compose.mainClock.autoAdvance=false
            compose.runOnUiThread {toggle()}
            repeat(24) {frame ->
                compose.mainClock.advanceTimeBy(32)
                Thread.sleep(35)
                val label="$variant-$length-expanding-$frame"
                val red=capture(label)
                if (if(variant=="frosted") red<45 else red>40) failures+=label
            }
            val close=compose.onNode(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription,"已展开"))
                .fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
            compose.runOnUiThread { close() }
            repeat(2) {
                compose.mainClock.advanceTimeBy(16);Thread.sleep(35)
                val red=capture("$variant-$length-closing-$it")
                if (if(variant=="frosted") red<45 else red>40) failures+="$variant/$length early restore"
            }
            compose.mainClock.autoAdvance=true
            compose.waitForIdle();compose.mainClock.advanceTimeBy(1000);compose.waitForIdle();Thread.sleep(150)
            if(capture("$variant-$length-restored-final")<45) failures+="$variant/$length never restored"
        }
        compose.mainClock.autoAdvance=true
        File(context.getExternalFilesDir(null),"reasoning-glass-samples.csv").writeText(samples.joinToString("\n"))
        assertTrue("Presentation mismatch: $failures; samples=$samples",failures.isEmpty())
    }
}
