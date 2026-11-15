package me.rerere.rikkahub.ui.pages.orbis

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.ai.IsolatedGenerationLoopRunner
import me.rerere.rikkahub.data.orbis.theme.*
import me.rerere.rikkahub.ui.components.message.*
import org.junit.Test
import java.io.File

/** Real-frame, multi-bubble diagnostic. Exercise simultaneous resize, scrolling and clipping,
 * with the same nested wallpaper sources as ChatPage. No private messages or live services. */
class ThemeScrollingGlassDeviceTest {
    @Test fun compareColoredNeutralAndBareGlassDuringScrolling() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        check(instrumentation is IsolatedGenerationLoopRunner && context.packageName.endsWith(".bodylab"))
        var expanded by mutableStateOf(false)
        var variant by mutableStateOf("colored")
        var move: (Float) -> Unit = {}
        var reset: () -> Unit = {}
        val bounds=java.util.concurrent.ConcurrentHashMap<Int,Rect>()
        val samples=java.util.concurrent.CopyOnWriteArrayList<String>()
        val events=java.util.concurrent.CopyOnWriteArrayList<String>()
        val start=android.os.SystemClock.elapsedRealtime()
        fun time()=android.os.SystemClock.elapsedRealtime()-start
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent {
                MaterialTheme {
                    val source=rememberHazeState()
                    val generalSource=rememberHazeState()
                    val list=rememberLazyListState()
                    val scope=rememberCoroutineScope()
                    move={ delta -> scope.launch { list.animateScrollBy(delta,tween(650)) } }
                    reset={scope.launch {list.scrollToItem(0)}}
                    LaunchedEffect(Unit) {
                        while(true) withFrameNanos {
                            samples+="${time()},$variant,$expanded,"+bounds.entries.sortedBy {it.key}.joinToString(";") {
                                val b=it.value;"${it.key}:${b.left}:${b.top}:${b.right}:${b.bottom}"
                            }
                        }
                    }
                    CompositionLocalProvider(LocalBubbleBackdrop provides source) {
                        Box(Modifier.fillMaxSize().background(Color(0xFF183249))) {
                            Box(Modifier.fillMaxSize().hazeSource(generalSource).hazeSource(source).background(Color(0xFF183249)))
                            val material=ThemeBubbleMaterial(type=if(variant=="frosted") "frosted" else "liquid",
                                blur=3f,depth=0f,tintOpacity=.3f,refraction=.9f,displacement=14f,flow=0f,
                                metallic=if(variant=="bare") 0f else .4f,dispersion=if(variant=="bare") 0f else .55f,
                                edgeColors=if(variant=="neutral") listOf("#FFFFFF","#F2F2F2","#FFFFFF","#EEEEEE") else ThemeBubbleMaterial().edgeColors,
                                edgeShadow=if(variant=="neutral") "#EEEEEE" else ThemeBubbleMaterial().edgeShadow,fold=.025f)
                            LazyColumn(Modifier.fillMaxSize().padding(top=92.dp,bottom=160.dp),state=list,
                                reverseLayout=true,contentPadding=PaddingValues(horizontal=22.dp,vertical=16.dp),
                                verticalArrangement=Arrangement.spacedBy(28.dp)) {
                                items(6,key={it}) { index ->
                                    val height by animateDpAsState(if(expanded && index==0) 1700.dp else 135.dp,tween(500))
                                    OrbisThemedBubble(false,ThemeBubble(bg="#EEF7FF",material=material,maxWidthRatio=1f),
                                        Modifier.fillMaxWidth().onGloballyPositioned {bounds[index]=it.boundsInWindow()}) {
                                        Box(Modifier.fillMaxWidth().height(height))
                                    }
                                }
                            }
                            Text("静态对照：$variant",Modifier.padding(22.dp,40.dp),color=Color.White)
                        }
                    }
                }
            } }
            Thread.sleep(1200)
            events+="${time()},record-start"
            val recording=instrumentation.uiAutomation.executeShellCommand("screenrecord --time-limit 32 --bit-rate 10000000 /sdcard/Download/axu-scrolling-glass.mp4")
            Thread.sleep(400)
            for(mode in listOf("colored","neutral","bare","frosted")) {
                instrumentation.runOnMainSync {variant=mode;expanded=false;reset()}
                Thread.sleep(450)
                events+="${time()},$mode-start"
                repeat(2) {
                    instrumentation.runOnMainSync {expanded=true};Thread.sleep(700)
                    instrumentation.runOnMainSync {move(1100f)};Thread.sleep(750)
                    instrumentation.runOnMainSync {move(-800f)};Thread.sleep(750)
                    instrumentation.runOnMainSync {expanded=false};Thread.sleep(700)
                }
                events+="${time()},$mode-end"
            }
            Thread.sleep(6000)
            recording.close()
            File(context.filesDir,"scroll-glass-bounds.csv").writeText(samples.joinToString("\n"))
            File(context.filesDir,"scroll-glass-events.csv").writeText(events.joinToString("\n"))
        }
    }
}
