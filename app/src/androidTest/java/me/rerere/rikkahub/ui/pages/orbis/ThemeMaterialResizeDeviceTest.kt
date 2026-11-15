package me.rerere.rikkahub.ui.pages.orbis

import androidx.activity.ComponentActivity
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import me.rerere.rikkahub.data.ai.IsolatedGenerationLoopRunner
import me.rerere.rikkahub.data.orbis.theme.*
import me.rerere.rikkahub.ui.components.message.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class ThemeMaterialResizeDeviceTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    @Test fun expandingAndCollapsingLongContentNeverDropsTheGlassLayer() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        check(instrumentation is IsolatedGenerationLoopRunner && context.packageName.endsWith(".bodylab"))
        val expanded=mutableStateOf(false)
        val sourceAvailable=mutableStateOf(true)
        val measuredHeight=java.util.concurrent.atomic.AtomicInteger()
        val background=Color(0xFF183249)
        val material=ThemeBubbleMaterial(type="liquid",blur=3f,depth=0f,tintOpacity=.3f,
            refraction=.9f,displacement=14f,flow=.85f,metallic=.4f,dispersion=.55f,fold=.025f)
        compose.setContent { MaterialTheme { ThemeMotionProvider(true) {
            val source=rememberHazeState()
            CompositionLocalProvider(LocalBubbleBackdrop provides source) {
                Box(Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxSize().background(background))
                    if(sourceAvailable.value) Box(Modifier.fillMaxSize().hazeSource(source).background(background))
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top=90.dp,start=22.dp)) {
                        OrbisThemedBubble(false,ThemeBubble(bg="#EEF7FF",material=material,maxWidthRatio=1f),Modifier.width(300.dp).onSizeChanged {measuredHeight.set(it.height)}.testTag("resize-glass")) {
                            Column(Modifier.animateContentSize()) {
                                Box(Modifier.fillMaxWidth().height(if(expanded.value) 1700.dp else 150.dp))
                            }
                        }
                    }
                }
            }
        } } }
        compose.waitForIdle();Thread.sleep(300)
        val bounds=compose.onNodeWithTag("resize-glass").fetchSemanticsNode().boundsInWindow
        val x=(bounds.left+bounds.width*.45f).toInt();val y=(bounds.top+60).toInt()
        compose.mainClock.autoAdvance=false
        val samples=mutableListOf<Int>()
        val heights=mutableListOf<Int>()
        repeat(6) {
            compose.runOnUiThread {expanded.value=!expanded.value}
            repeat(12) {
                compose.mainClock.advanceTimeBy(48)
                Thread.sleep(35)
                val bitmap=instrumentation.uiAutomation.takeScreenshot()
                samples+=android.graphics.Color.red(bitmap.getPixel(x,y));bitmap.recycle()
                heights+=measuredHeight.get()
            }
        }
        // A source can be unavailable briefly while the scrolling/relayout tree
        // updates. The last wallpaper material must cover this gap, never text.
        compose.runOnUiThread {sourceAvailable.value=false}
        repeat(4) {
            compose.mainClock.advanceTimeBy(48)
            Thread.sleep(35)
            val bitmap=instrumentation.uiAutomation.takeScreenshot()
            samples+=android.graphics.Color.red(bitmap.getPixel(x,y));bitmap.recycle()
        }
        compose.runOnUiThread {sourceAvailable.value=true}
        compose.mainClock.advanceTimeBy(48)
        File(context.getExternalFilesDir(null),"glass-resize-samples.txt").writeText(samples.joinToString(","))
        assertTrue("test must exercise real changing bounds: $heights",heights.max()>heights.min()*3)
        // The unmodified wallpaper is red=24; a .3 white tint remains distinctly brighter.
        assertTrue("material vanished during resize: min=${samples.min()} samples=$samples",samples.min()>45)
    }
}
