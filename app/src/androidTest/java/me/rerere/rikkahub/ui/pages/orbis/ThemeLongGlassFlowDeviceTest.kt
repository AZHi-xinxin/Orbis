package me.rerere.rikkahub.ui.pages.orbis

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import me.rerere.rikkahub.data.ai.IsolatedGenerationLoopRunner
import me.rerere.rikkahub.data.orbis.theme.*
import me.rerere.rikkahub.ui.components.message.*
import kotlinx.coroutines.launch
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import kotlin.math.abs

/** Exercise the optical edge itself: a center-tint check cannot detect a dropped flow pass. */
class ThemeLongGlassFlowDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun longExpandedAndScrolledBubbleKeepsItsOpticalEdge() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        check(instrumentation is IsolatedGenerationLoopRunner && context.packageName.endsWith(".bodylab"))
        val clock = mutableStateOf(0L)
        var expanded by mutableStateOf(false)
        var static by mutableStateOf(false)
        var bounds = Rect.Zero
        var fullHeight = 0
        var scroll: (Int) -> Unit = {}
        val results = mutableListOf<String>()
        val failures = mutableListOf<String>()
        val output = File(context.filesDir, "long-glass-flow").apply { mkdirs() }
        compose.setContent {
            MaterialTheme {
                val backdrop = rememberHazeState()
                val scrolling = rememberScrollState()
                val scope = rememberCoroutineScope()
                scroll = { offset -> scope.launch { scrolling.scrollTo(offset) } }
                CompositionLocalProvider(LocalBubbleBackdrop provides backdrop, LocalThemeMotionClock provides clock) {
                    Box(Modifier.fillMaxSize()) {
                        Canvas(Modifier.fillMaxSize().hazeSource(backdrop)) {
                            drawRect(Color(0xFFE2ECF1))
                            val step = 13.dp.toPx()
                            for (i in 0..(size.width / step).toInt()) drawRect(
                                if (i % 2 == 0) Color(0xFF314B5E) else Color(0xFF98C7D6),
                                Offset(i * step, 0f), Size(step * .6f, size.height))
                        }
                        Column(Modifier.fillMaxSize().verticalScroll(scrolling).padding(start=22.dp, top=90.dp)) {
                            OrbisThemedBubble(false, ThemeBubble(bg="#EEF7FF", maxWidthRatio=1f,
                                material=ThemeBubbleMaterial(type="liquid", blur=0f, depth=0f, tintOpacity=.12f,
                                    refraction=.8f, displacement=12f, dispersion=.55f,
                                    flow=if(static) 0f else .9f, metallic=.45f, fold=.05f)),
                                Modifier.width(300.dp).onGloballyPositioned {
                                    bounds=it.boundsInWindow(); fullHeight=it.size.height
                                }) {
                                Box(Modifier.fillMaxWidth().height(if(expanded) 2400.dp else 150.dp))
                            }
                        }
                    }
                }
            }
        }
        fun settle() { compose.waitForIdle(); Thread.sleep(250); compose.waitForIdle() }
        fun capture(label: String): Bitmap {
            settle()
            return instrumentation.uiAutomation.takeScreenshot().also { bitmap ->
                File(output,"$label.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
            }
        }
        for (mode in listOf("dynamic", "static")) {
            compose.runOnIdle { static=mode=="static" }
            for (state in listOf("short", "expanded", "scrolled", "collapsed")) {
                compose.runOnIdle { expanded=state=="expanded" || state=="scrolled"; clock.value=0L }
                settle()
                compose.runOnIdle { scroll(if(state=="scrolled") 1800 else 0) }
                val first=capture("$mode-$state-a")
                val area=bounds
                val height=fullHeight
                compose.runOnIdle { clock.value=1750L }
                val second=capture("$mode-$state-b")
                var delta=0.0; var count=0
                // Sample the visible left edge; avoid corners and system bars.
                val x0=(area.left+8).toInt(); val x1=(area.left+area.width*.22f).toInt()
                val y0=(area.top+area.height*.25f).toInt(); val y1=(area.top+area.height*.75f).toInt()
                for(y in y0 until y1 step 2) for(x in x0 until x1 step 2) {
                    val a=first.getPixel(x,y); val b=second.getPixel(x,y)
                    delta+=abs(android.graphics.Color.red(a)-android.graphics.Color.red(b))
                    count++
                }
                val difference=delta/count.coerceAtLeast(1)
                results+="$mode,$state,$height,$count,$difference"
                if(count==0 || (mode=="dynamic" && difference<2.0) || (mode=="static" && difference>.1))
                    failures+="$mode/$state: edge delta=$difference, height=$height"
                if(state=="expanded" && height<=4096) failures+="fixture failed to exceed old layer cap: $height"
                first.recycle();second.recycle()
            }
        }
        File(output,"results.csv").writeText(results.joinToString("\n"))
        assertTrue(failures.joinToString("\n"),failures.isEmpty())
    }
}
