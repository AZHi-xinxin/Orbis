package me.rerere.rikkahub.ui.pages.orbis

import androidx.activity.ComponentActivity
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import me.rerere.rikkahub.data.ai.IsolatedGenerationLoopRunner
import me.rerere.rikkahub.data.orbis.theme.*
import me.rerere.rikkahub.ui.components.ai.ChatComposerSurface
import me.rerere.rikkahub.ui.components.ai.chatInputFieldColors
import me.rerere.rikkahub.ui.components.message.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class ThemeSurfaceFlowDeviceTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    private val glass=ThemeBubbleMaterial(type="liquid",blur=0f,depth=0f,tintOpacity=.12f,
        refraction=.8f,displacement=12f,dispersion=.55f,flow=.9f,flowSpeed=.85f,metallic=.45f,fold=.05f,refractionHeight=.18f)
    private fun checkIsolated() {
        check(InstrumentationRegistry.getInstrumentation() is IsolatedGenerationLoopRunner)
        check(InstrumentationRegistry.getInstrumentation().targetContext.packageName.endsWith(".bodylab"))
    }
    private fun settle() { compose.waitForIdle();Thread.sleep(250);compose.waitForIdle() }

    @Test fun nativeFlowActuallyMovesBackgroundButNeverMovesInk() {
        checkIsolated();val clock=mutableStateOf(0L)
        compose.setContent {
            MaterialTheme {
                val backdrop=rememberHazeState()
                CompositionLocalProvider(LocalBubbleBackdrop provides backdrop,LocalThemeMotionClock provides clock) {
                    Box(Modifier.fillMaxSize()) {
                        Canvas(Modifier.fillMaxSize().hazeSource(backdrop)) {
                            drawRect(Color(0xFFE2ECF1));val step=13.dp.toPx()
                            for(i in 0..(size.width/step).toInt()) drawRect(if(i%2==0) Color(0xFF314B5E) else Color(0xFF98C7D6),Offset(i*step,0f),Size(step*.6f,size.height))
                        }
                        OrbisThemedBubble(false,ThemeBubble(material=glass,maxWidthRatio=1f),Modifier.align(Alignment.Center).width(290.dp).testTag("flow")) {
                            Box(Modifier.fillMaxWidth().height(110.dp)) {
                                Box(Modifier.align(Alignment.Center).size(16.dp).background(Color.Blue).testTag("fixed-ink"))
                            }
                        }
                    }
                }
            }
        }
        settle();val first=compose.onNodeWithTag("flow").captureToImage().toPixelMap()
        compose.runOnIdle { clock.value=1750L }
        settle();val later=compose.onNodeWithTag("flow").captureToImage().toPixelMap()
        var delta=0.0;var n=0
        for(y in first.height/4 until first.height*3/4) for(x in 5 until first.width/4) { delta+=abs(first[x,y].red-later[x,y].red);n++ }
        assertTrue("background really flows across frames: ${delta/n}",delta/n>.02)
        val ink=compose.onNodeWithTag("fixed-ink").captureToImage().toPixelMap()
        assertEquals(Color.Blue,ink[ink.width/2,ink.height/2])
    }

    @Test fun editorAndNavigationRetainActionsAndRecordNativeFlow() {
        checkIsolated();val clock=mutableStateOf(0L)
        var text by mutableStateOf("");var sends=0;var homes=0;var nav=0
        val body=ThemePackage(name="流动水银",chrome=ThemeChrome(surface=ThemeSurface(material=glass)))
        compose.setContent {
            MaterialTheme {
                val backdrop=rememberHazeState()
                CompositionLocalProvider(LocalChatTheme provides ChatTheme(body,null),LocalBubbleBackdrop provides backdrop,LocalThemeMotionClock provides clock) {
                    OrbisVisualTheme {
                        val focus=LocalFocusManager.current
                        Box(Modifier.fillMaxSize()) {
                            Image(painterResource(me.rerere.rikkahub.R.drawable.market_cover_cafe),null,
                                Modifier.fillMaxSize().hazeSource(backdrop),contentScale=ContentScale.Crop)
                            Column(Modifier.fillMaxSize().padding(top=42.dp,bottom=30.dp),verticalArrangement=Arrangement.spacedBy(20.dp)) {
                                Text("流动的透明水面",Modifier.padding(horizontal=22.dp),color=Color(0xFF203C52),fontSize=25.sp)
                                Text("气泡 · 输入框 · 北斗导航",Modifier.padding(horizontal=22.dp),color=Color(0xFF294B65),fontSize=14.sp)
                                OrbisThemedBubble(false,ThemeBubble(material=glass,radius=28),Modifier.fillMaxWidth().padding(horizontal=22.dp)) {
                                    Text("背景从边缘折进来，\n像水面，也有金属的微光。",fontSize=16.sp)
                                }
                                OrbisThemedBubble(true,ThemeBubble(bg="#E8F8FF",material=glass.copy(metallic=.55f),radius=26),Modifier.fillMaxWidth().padding(horizontal=22.dp)) {
                                    Text("水在流动，字安静地留在这里。",fontSize=16.sp)
                                }
                                Spacer(Modifier.weight(1f))
                                ChatComposerSurface(Modifier.fillMaxWidth().padding(horizontal=14.dp),RoundedCornerShape(23.dp),Color.White,Color(0xFF263342),BorderStroke(1.dp,Color.White.copy(alpha=.6f)),true) {
                                    Row(verticalAlignment=Alignment.CenterVertically) {
                                        TextField(text,{text=it},Modifier.weight(1f).testTag("material-input"),placeholder={Text("写点什么…")},colors=chatInputFieldColors())
                                        TextButton({sends++;focus.clearFocus()},Modifier.testTag("material-send")) { Text("↑") }
                                    }
                                }
                                OrbisBottomDock("当前 聊天",{homes++},{nav++},windowInsets=WindowInsets(0,0,0,0))
                            }
                        }
                    }
                }
            }
        }
        compose.onNodeWithTag("material-input").performTextInput("流动的玻璃")
        compose.onNodeWithTag("material-send").performClick();assertEquals("流动的玻璃",text);assertEquals(1,sends)
        compose.onNodeWithContentDescription("打开北斗导航").performClick();assertEquals(1,nav)
        compose.onNodeWithContentDescription("返回 Orbis 主页").performClick();assertEquals(1,homes)
        settle()
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val bitmap=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(context.getExternalFilesDir(null),"bubble-liquid-chrome.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) };bitmap.recycle()
        // Synthetic fixture only. Manual time drives the same graphics-layer shader as the real shared clock.
        val recording=InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("screenrecord --time-limit 6 /sdcard/bubble-liquid-chrome.mp4")
        repeat(110) { frame -> compose.runOnUiThread { clock.value=frame*55L }; Thread.sleep(55) }
        recording.close()
    }
}
