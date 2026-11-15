package me.rerere.rikkahub.ui.pages.orbis

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import me.rerere.rikkahub.data.ai.IsolatedGenerationLoopRunner
import me.rerere.rikkahub.data.orbis.theme.*
import me.rerere.rikkahub.ui.components.message.*
import me.rerere.rikkahub.ui.components.ui.ChainOfThought
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class ThemeGlassDetailPresentationDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun groupAndMultipleInlineDetailsKeepOneContinuousOutlineThroughRapidToggles() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        check(instrumentation is IsolatedGenerationLoopRunner && context.packageName.endsWith(".bodylab"))
        var bounds = Rect.Zero
        var detailState: LiquidGlassDetailState? = null
        compose.setContent { MaterialTheme {
            val source = rememberHazeState()
            CompositionLocalProvider(LocalBubbleBackdrop provides source) {
                Box(Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxSize().hazeSource(source).background(Color(0xFF183249)))
                    OrbisThemedBubble(false, ThemeBubble(bg="#EEF7FF", maxWidthRatio=1f,
                        material=ThemeBubbleMaterial(type="liquid", blur=3f, depth=0f, tintOpacity=.3f)),
                        Modifier.padding(top=90.dp,start=22.dp).width(300.dp)
                            .onGloballyPositioned { bounds=it.boundsInWindow() }) {
                        detailState=LocalLiquidGlassDetails.current
                        ChainOfThought(steps=listOf("A","B","C","D")) { label ->
                            key(label) {
                            ChainOfThoughtStep(label={Text("Detail $label")},content={
                                Box(Modifier.fillMaxWidth().height(30.dp))
                            })
                            }
                        }
                        Box(Modifier.fillMaxWidth().height(60.dp))
                    }
                }
            }
        } }
        val samples=mutableListOf<String>()
        fun sample(label:String, outlined:Boolean) {
            val bitmap=instrumentation.uiAutomation.takeScreenshot()
            val red=android.graphics.Color.red(bitmap.getPixel((bounds.left+bounds.width*.5f).toInt(),(bounds.bottom-60).toInt()))
            bitmap.recycle();samples+="$label,$red,$outlined,${detailState?.expanded},${detailState?.settling}"
            File(context.filesDir,"detail-outline-samples.csv").writeText(samples.joinToString("\n"))
            assertTrue("$label red=$red expected outline=$outlined", if(outlined) red<40 else red>45)
        }
        fun tap(node:SemanticsNodeInteraction) {
            val action=node.fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
            compose.runOnUiThread {action()}
            compose.mainClock.advanceTimeBy(16)
            Thread.sleep(40)
        }
        fun settle() { compose.mainClock.autoAdvance=true;compose.waitForIdle();compose.mainClock.advanceTimeBy(1000);compose.waitForIdle();Thread.sleep(150);compose.mainClock.autoAdvance=false }
        settle();sample("initial",false)
        tap(compose.onNodeWithTag("chain-of-thought-toggle"));sample("group-opening",true)
        settle();sample("group-open",true)
        tap(compose.onNodeWithTag("chain-of-thought-toggle"));sample("group-closing",true)
        settle();sample("group-restored",false)
        tap(compose.onNodeWithText("Detail C"));sample("C-open",true)
        tap(compose.onNodeWithText("Detail D"));sample("D-open",true)
        settle();sample("two-still-open",true)
        tap(compose.onNodeWithText("Detail C"));settle();sample("one-still-open",true)
        tap(compose.onNodeWithText("Detail D"));sample("last-closing",true)
        tap(compose.onNodeWithText("Detail D"));sample("rapid-reopen",true)
        settle();sample("reopen-stays-transparent",true)
        tap(compose.onNodeWithText("Detail D"));sample("closing-again",true)
        settle();sample("restored-after-stable",false)
        File(context.filesDir,"detail-outline-samples.csv").writeText(samples.joinToString("\n"))
    }
}
