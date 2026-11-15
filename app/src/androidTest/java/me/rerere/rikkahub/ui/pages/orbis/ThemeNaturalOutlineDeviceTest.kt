package me.rerere.rikkahub.ui.pages.orbis

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import me.rerere.rikkahub.data.ai.IsolatedGenerationLoopRunner
import me.rerere.rikkahub.data.orbis.theme.*
import me.rerere.rikkahub.ui.components.message.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/** Uses the real Android frame clock, so a paused Compose test clock cannot hide timer errors. */
class ThemeNaturalOutlineDeviceTest {
    @Test fun realClockKeepsTransparentDetailsAndRestoresOnlyAfterCollapseSettles() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        check(instrumentation is IsolatedGenerationLoopRunner && context.packageName.endsWith(".bodylab"))
        var expanded by mutableStateOf(false)
        var mode by mutableStateOf("dynamic")
        val bounds=AtomicReference(Rect.Zero)
        val samples=mutableListOf<String>()
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent { MaterialTheme { ThemeMotionProvider(true) {
                val source=rememberHazeState()
                CompositionLocalProvider(LocalBubbleBackdrop provides source) {
                    Box(Modifier.fillMaxSize()) {
                        Box(Modifier.fillMaxSize().hazeSource(source).background(Color(0xFF183249)))
                        OrbisThemedBubble(false,ThemeBubble(bg="#EEF7FF",maxWidthRatio=1f,
                            material=ThemeBubbleMaterial(type=if(mode=="frosted") "frosted" else "liquid",
                                blur=3f,depth=0f,tintOpacity=.3f,flow=if(mode=="static") 0f else .85f)),
                            Modifier.padding(top=90.dp,start=22.dp).width(300.dp).onGloballyPositioned {bounds.set(it.boundsInWindow())}) {
                            ReportLiquidGlassDetail(expanded)
                            Column(Modifier.animateContentSize()) {
                                Box(Modifier.fillMaxWidth().height(if(expanded) 500.dp else 150.dp))
                            }
                        }
                    }
                }
            } } } }
            fun sample(label:String,outline:Boolean) {
                val b=bounds.get();val bitmap=instrumentation.uiAutomation.takeScreenshot()
                val red=android.graphics.Color.red(bitmap.getPixel((b.left+b.width*.5f).toInt(),(b.top+60).toInt()))
                bitmap.recycle();samples+="$mode,$label,$red,$outline"
                File(context.filesDir,"natural-outline-samples.csv").writeText(samples.joinToString("\n"))
                assertTrue("$mode/$label red=$red expected outline=$outline",if(outline) red<40 else red>45)
            }
            for(variant in listOf("dynamic","static","frosted")) {
                instrumentation.runOnMainSync {mode=variant;expanded=false};Thread.sleep(900)
                sample("initial",false)
                instrumentation.runOnMainSync {expanded=true};Thread.sleep(40)
                repeat(12) {sample("open-$it",variant!="frosted");Thread.sleep(25)}
                instrumentation.runOnMainSync {expanded=false};Thread.sleep(40)
                sample("closing",variant!="frosted")
                instrumentation.runOnMainSync {expanded=true};Thread.sleep(40)
                repeat(4) {sample("reopened-$it",variant!="frosted");Thread.sleep(25)}
                instrumentation.runOnMainSync {expanded=false};Thread.sleep(40)
                sample("closing-again",variant!="frosted")
                Thread.sleep(1200);sample("restored",false)
            }
        }
    }
}
