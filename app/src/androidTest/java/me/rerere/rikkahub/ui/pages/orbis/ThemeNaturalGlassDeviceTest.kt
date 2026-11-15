package me.rerere.rikkahub.ui.pages.orbis

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import me.rerere.rikkahub.data.ai.IsolatedGenerationLoopRunner
import me.rerere.rikkahub.data.orbis.theme.*
import me.rerere.rikkahub.ui.components.message.*
import org.junit.Test
import java.io.File

/** Uses the real Android frame clock. Compose test-clock advancement plus blocking screenshots
 * can hide one-frame RenderNode lifetime bugs, so sample this fixture from its continuous video. */
class ThemeNaturalGlassDeviceTest {
    @Test fun naturalResizeRecording() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        check(instrumentation is IsolatedGenerationLoopRunner && context.packageName.endsWith(".bodylab"))
        var expanded by mutableStateOf(false)
        var variant by mutableStateOf("liquid")
        val samples=java.util.concurrent.CopyOnWriteArrayList<String>()
        val start=android.os.SystemClock.elapsedRealtime()
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent {
                MaterialTheme { ThemeMotionProvider(true) {
                    val backdrop=rememberHazeState()
                    val height by animateDpAsState(if(expanded) 1700.dp else 140.dp,tween(550))
                    CompositionLocalProvider(LocalBubbleBackdrop provides backdrop) {
                        Box(Modifier.fillMaxSize().background(Color(0xFF183249))) {
                            Box(Modifier.fillMaxSize().hazeSource(backdrop).background(Color(0xFF183249)))
                            val material=ThemeBubbleMaterial(type=if(variant=="frosted") "frosted" else "liquid",blur=3f,
                                depth=0f,tintOpacity=.3f,refraction=.9f,displacement=14f,
                                flow=if(variant=="liquid") .85f else 0f,
                                metallic=if(variant=="no-flow-layer") 0f else .4f,
                                dispersion=if(variant=="no-flow-layer") 0f else .55f,fold=.025f)
                            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start=22.dp,top=90.dp)) {
                            OrbisThemedBubble(false,ThemeBubble(bg="#EEF7FF",material=material,maxWidthRatio=1f),
                                Modifier.width(300.dp).onGloballyPositioned {
                                    val b=it.boundsInWindow()
                                    samples+="${android.os.SystemClock.elapsedRealtime()-start},$variant,$expanded,${b.left},${b.top},${b.width},${b.height}"
                                }) { Box(Modifier.fillMaxWidth().height(height)) }
                            }
                        }
                    }
                } }
            } }
            Thread.sleep(1500)
            val recording=instrumentation.uiAutomation.executeShellCommand("screenrecord --time-limit 26 --bit-rate 8000000 /sdcard/Download/axu-natural-glass.mp4")
            Thread.sleep(500)
            for(mode in listOf("liquid","static","no-flow-layer","frosted")) {
                instrumentation.runOnMainSync {variant=mode;expanded=false}
                Thread.sleep(500)
                repeat(6) {
                    instrumentation.runOnMainSync {expanded=!expanded}
                    Thread.sleep(700)
                }
            }
            Thread.sleep(6000)
            recording.close()
            File(context.filesDir,"natural-glass-bounds.csv").writeText(samples.joinToString("\n"))
        }
    }
}
