package me.rerere.rikkahub.ui.pages.orbis

import androidx.activity.ComponentActivity
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.chrisbanes.haze.rememberHazeState
import dev.chrisbanes.haze.hazeSource
import me.rerere.rikkahub.data.ai.IsolatedGenerationLoopRunner
import me.rerere.rikkahub.data.orbis.theme.*
import me.rerere.rikkahub.ui.components.message.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class ThemeMaterialDeviceTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    private fun checkIsolated() {
        check(InstrumentationRegistry.getInstrumentation() is IsolatedGenerationLoopRunner)
        check(InstrumentationRegistry.getInstrumentation().targetContext.packageName.endsWith(".bodylab"))
    }
    private fun settle() { compose.waitForIdle(); Thread.sleep(350); compose.waitForIdle() }
    private fun capture(name: String) {
        settle()
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val bitmap=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(context.getExternalFilesDir(null),"bubble-$name.png").outputStream().use {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)
        }; bitmap.recycle()
    }
    // Capture the composed window pixels directly. Compose's forceRedraw/PixelCopy helper has
    // a fixed 2s deadline that intermittently expires during native Haze layer replacement.
    // Keep the same pixel assertions; do not retry or relax their thresholds.
    private fun windowPixels(tag: String): PixelMap {
        val bounds=compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInWindow
        val full=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        val crop=android.graphics.Bitmap.createBitmap(full,bounds.left.toInt(),bounds.top.toInt(),
            bounds.width.toInt(),bounds.height.toInt())
        val pixels=crop.asImageBitmap().toPixelMap()
        crop.recycle();full.recycle()
        return pixels
    }
    private fun variation(p: PixelMap): Double {
        var sum=0.0;var n=0
        for(y in p.height/3 until p.height*2/3) for(x in p.width/4 until p.width*3/4-1) {
            sum+=abs(p[x,y].red-p[x+1,y].red);n++
        }
        return sum/n
    }
    @Test fun importedTextureRepeatsAtItsAspectRatioInsteadOfStretchingLongMessages() {
        checkIsolated()
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val file=File(context.cacheDir,"synthetic-ice-tile.png")
        val bitmap=android.graphics.Bitmap.createBitmap(32,64,android.graphics.Bitmap.Config.ARGB_8888)
        for(y in 0 until 64) for(x in 0 until 32) bitmap.setPixel(x,y,if(y<32) android.graphics.Color.BLUE else android.graphics.Color.YELLOW)
        file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) };bitmap.recycle()
        val asset="c".repeat(64)+".png"
        val bubble=ThemeBubble(maxWidthRatio=1f,texture=ThemeBubbleTexture(type="image",asset=asset,scale=.5f,opacity=1f,edgeFade=0f))
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalChatTheme provides ChatTheme(ThemePackage(name="tile",assets=listOf(asset)),null,assets=mapOf(asset to file.absolutePath))) {
                    OrbisThemedBubble(false,bubble,Modifier.width(240.dp).testTag("tile")) {
                        Box(Modifier.fillMaxWidth().height(390.dp))
                    }
                }
            }
        }
        settle()
        compose.waitUntil(5000) {
            val p=compose.onNodeWithTag("tile").captureToImage().toPixelMap()
            p[(p.width*.18f).toInt(),(p.width*45/240f).toInt()].blue>.9f &&
                p[(p.width*.18f).toInt(),(p.width*45/240f).toInt()].red<.1f
        }
        val p=compose.onNodeWithTag("tile").captureToImage().toPixelMap();val density=p.width/240f
        assertEquals(Color.Blue,p[(40*density).toInt(),(45*density).toInt()])
        assertEquals(Color.Yellow,p[(40*density).toInt(),(135*density).toInt()])
        assertEquals(Color.Blue,p[(40*density).toInt(),(225*density).toInt()])
        file.delete()
    }
    @Test fun iceTextureIsStableAndCanBeRemovedWithoutChangingText() {
        checkIsolated()
        val enabled=mutableStateOf(true)
        compose.setContent {
            MaterialTheme {
                OrbisThemedBubble(false,ThemeBubble(bg="#326A88",text="#FFFFFF",maxWidthRatio=1f,
                    texture=if(enabled.value) ThemeBubbleTexture(edgeFade=1f,opacity=1f,density=1f) else null),
                    Modifier.width(280.dp).testTag("ice")) {
                    Box(Modifier.fillMaxWidth().height(160.dp)) {
                        Box(Modifier.align(Alignment.Center).size(12.dp).background(Color.Blue).testTag("ice-ink"))
                    }
                }
            }
        }
        settle();val before=compose.onNodeWithTag("ice").captureToImage().toPixelMap()
        settle();val again=compose.onNodeWithTag("ice").captureToImage().toPixelMap()
        compose.runOnIdle { enabled.value=false }
        settle();val plain=compose.onNodeWithTag("ice").captureToImage().toPixelMap()
        var changed=0;var same=0
        for(y in before.height/4 until before.height*3/4) for(x in 8 until before.width/4) {
            if(abs(before[x,y].red-plain[x,y].red)>.03f) changed++
            if(before[x,y]==again[x,y]) same++
        }
        assertTrue("feather crystal lines are rendered",changed>100)
        assertTrue("fixed seed is stable without a timer",same>100)
        val ink=compose.onNodeWithTag("ice-ink").captureToImage().toPixelMap()
        assertEquals(Color.Blue,ink[ink.width/2,ink.height/2])
    }

    @Test fun iceCrystalsRefractSharpWallpaperAboveBlurWithoutDistortingInk() {
        checkIsolated()
        val crystal=mutableStateOf(false);val bend=mutableFloatStateOf(0f)
        compose.setContent {
            MaterialTheme {
                val backdrop=rememberHazeState()
                CompositionLocalProvider(LocalBubbleBackdrop provides backdrop) {
                    Box(Modifier.fillMaxSize()) {
                        Canvas(Modifier.fillMaxSize().hazeSource(backdrop)) {
                            drawRect(Color(0xFFE1F4FF));val step=6.dp.toPx()
                            for(i in 0..(size.width/step).toInt()) drawRect(Color(0xFF234E79),Offset(i*step,0f),Size(step*.5f,size.height))
                        }
                        OrbisThemedBubble(false,ThemeBubble(bg="#E4F5FF",maxWidthRatio=1f,
                            material=ThemeBubbleMaterial(blur=20f,tintOpacity=.15f,refraction=1f,displacement=bend.floatValue),
                            texture=if(crystal.value) ThemeBubbleTexture(opacity=1f,edgeFade=.4f,density=1f) else null),
                            Modifier.align(Alignment.Center).width(290.dp).testTag("optical-ice")) {
                            Box(Modifier.fillMaxWidth().height(160.dp)) {
                                Box(Modifier.align(Alignment.Center).size(16.dp).background(Color.Blue).testTag("crystal-ink"))
                            }
                        }
                    }
                }
            }
        }
        settle();val frost=compose.onNodeWithTag("optical-ice").captureToImage().toPixelMap()
        compose.runOnIdle { crystal.value=true }
        settle();val flat=compose.onNodeWithTag("optical-ice").captureToImage().toPixelMap()
        compose.runOnIdle { bend.floatValue=18f }
        settle();val refracted=compose.onNodeWithTag("optical-ice").captureToImage().toPixelMap()
        var clarity=0.0;var warped=0.0;var n=0
        for(y in flat.height/5 until flat.height*4/5) for(x in 12 until flat.width/3) {
            clarity+=abs(flat[x,y].red-frost[x,y].red)
            warped+=abs(flat[x,y].red-refracted[x,y].red);n++
        }
        assertTrue("crystal facets reveal sharper wallpaper: ${clarity/n}",clarity/n>.025)
        assertTrue("crystal normals really refract wallpaper: ${warped/n}",warped/n>.02)
        val ink=compose.onNodeWithTag("crystal-ink").captureToImage().toPixelMap()
        assertEquals(Color.Blue,ink[ink.width/2,ink.height/2])
    }

    @Test fun nativeIceWindowShowcase() {
        checkIsolated()
        compose.setContent {
            MaterialTheme {
                val backdrop=rememberHazeState()
                CompositionLocalProvider(LocalBubbleBackdrop provides backdrop) {
                    Box(Modifier.fillMaxSize()) {
                        Image(painterResource(me.rerere.rikkahub.R.drawable.market_cover_rain),null,
                            Modifier.fillMaxSize().hazeSource(backdrop),contentScale=ContentScale.Crop)
                        Column(Modifier.fillMaxSize().padding(horizontal=22.dp,vertical=48.dp),verticalArrangement=Arrangement.spacedBy(24.dp)) {
                            Text("霜落在窗上",color=Color(0xFFE8F6FF),fontSize=26.sp)
                            Text("雾面冰层 · 透明晶脊 · 背景折射",color=Color(0xFFD7EAF5),fontSize=13.sp)
                            OrbisThemedBubble(false,ThemeBubble(bg="#DDEFF9",material=ThemeBubbleMaterial(blur=14f,tintOpacity=.28f,displacement=14f,refraction=.85f),
                                texture=ThemeBubbleTexture(opacity=1f,density=.9f,scale=1.1f,edgeFade=.8f,seed=19)),Modifier.fillMaxWidth()) {
                                Text("窗外的灯光在雾里晕开，\n冰晶却是清凉、透亮的。\n你的话留在清楚的这一面。",fontSize=16.sp)
                            }
                            OrbisThemedBubble(true,ThemeBubble(bg="#E1E7FD",text="#24384C",radius=26,
                                material=ThemeBubbleMaterial(blur=12f,tintOpacity=.32f,displacement=18f,refraction=1f),
                                texture=ThemeBubbleTexture(color="#CBEAFF",opacity=.9f,density=.8f,scale=.85f,edgeFade=.85f,seed=41)),Modifier.fillMaxWidth()) {
                                Text("透明的晶脊里，\n背景轻轻折了一下。\n像一小片真正的冰。",fontSize=16.sp)
                            }
                            OrbisThemedBubble(false,ThemeBubble(bg="#D6EAF5",material=ThemeBubbleMaterial(blur=18f,tintOpacity=.32f,displacement=12f,refraction=.8f),
                                texture=ThemeBubbleTexture(opacity=.9f,density=1f,scale=1.5f,edgeFade=.95f,seed=3)),Modifier.fillMaxWidth()) {
                                Text("冰面柔和，晶体坚硬。\n\n背景经过这里，会模糊、弯折；文字始终清晰。",fontSize=16.sp)
                            }
                            Text("原生材质实拍 · 气泡、输入框、底栏可共用",color=Color(0xFFD7EAF5),fontSize=12.sp)
                        }
                    }
                }
            }
        }
        capture("ice-window")
    }
    @Test fun blurAndRefractionChangeOnlyWallpaperWhileTextAndClicksStaySharp() {
        checkIsolated()
        val style=mutableStateOf(ThemeBubble(radius=26,maxWidthRatio=1f,opacity=0f))
        var clicks=0
        compose.setContent {
            MaterialTheme {
                val backdrop=rememberHazeState()
                CompositionLocalProvider(LocalBubbleBackdrop provides backdrop) {
                    Box(Modifier.fillMaxSize()) {
                        Canvas(Modifier.fillMaxSize().hazeSource(backdrop)) {
                            drawRect(Color(0xFF163C51))
                            val cell=7.dp.toPx()
                            for(y in 0..(size.height/cell).toInt()) for(x in 0..(size.width/cell).toInt())
                                if((x+y)%2==0) drawRect(Color(0xFFE9B890),Offset(x*cell,y*cell),Size(cell,cell))
                        }
                        OrbisThemedBubble(false,style.value,Modifier.align(Alignment.Center).width(280.dp).testTag("bubble")) {
                            Box(Modifier.fillMaxWidth().height(112.dp)) {
                                Box(Modifier.size(12.dp).background(Color.Blue).testTag("ink").clickable { clicks++ })
                            }
                        }
                    }
                }
            }
        }
        settle(); val raw=windowPixels("bubble")
        compose.runOnIdle { style.value=style.value.copy(opacity=1f, material=ThemeBubbleMaterial(blur=24f,tintOpacity=0f,highlight=0f,rim=0f)) }
        settle(); val blurred=windowPixels("bubble")
        assertTrue("real backdrop blur: ${variation(raw)} -> ${variation(blurred)}", variation(blurred)<variation(raw)*.5)
        val glass=ThemeBubbleMaterial(type="liquid",blur=0f,depth=0f,tintOpacity=0f,refraction=0f,highlight=0f,rim=0f,dispersion=0f)
        compose.runOnIdle { style.value=style.value.copy(material=glass) }
        settle(); val flat=windowPixels("bubble")
        compose.runOnIdle { style.value=style.value.copy(material=glass.copy(refraction=1f,displacement=20f,dispersion=.6f)) }
        settle(); val curved=windowPixels("bubble")
        var delta=0.0;var n=0
        for(y in curved.height/4 until curved.height*3/4) for(x in 4 until curved.width/5) {
            delta+=abs(curved[x,y].red-flat[x,y].red);n++
        }
        assertTrue("native refraction has visible displacement: ${delta/n}",delta/n>.015)
        val ink=windowPixels("ink")
        assertEquals(0f,ink[ink.width/2,ink.height/2].red,.01f)
        assertEquals(1f,ink[ink.width/2,ink.height/2].blue,.01f)
        compose.onNodeWithTag("ink").performClick(); assertEquals(1,clicks)
    }

    @Test fun stickersStayFixedSizeOutsideTextWhenMessageGrowsAndFallbackIsReadable() {
        checkIsolated()
        val height=mutableIntStateOf(24)
        val opacity=mutableFloatStateOf(1f)
        val sticker=ThemeSticker(size=40, drawing=ThemeDrawing(layers=listOf(
            ThemeVectorLayer("M0 0 L100 0 L100 100 L0 100 Z",fill="#E000CC"))))
        val bubble=ThemeBubble(material=ThemeBubbleMaterial(type="liquid"),stickers=listOf(sticker),maxWidthRatio=1f)
        compose.setContent {
            MaterialTheme {
                Box(Modifier.fillMaxSize().background(Color.Black)) {
                    OrbisThemedBubble(false,bubble.copy(opacity=opacity.floatValue),Modifier.width(260.dp).testTag("sticker")) {
                        Box(Modifier.fillMaxWidth().height(height.intValue.dp).testTag("body"))
                    }
                }
            }
        }
        fun count(): Int {
            settle();val p=compose.onNodeWithTag("sticker").captureToImage().toPixelMap()
            var count=0
            for(y in 0 until p.height) for(x in 0 until p.width) if(p[x,y].red>.7f && p[x,y].green<.1f && p[x,y].blue>.6f) count++
            return count
        }
        val small=count();assertTrue(small>100)
        compose.runOnIdle { height.intValue=260 }
        val tall=count();assertEquals("sticker does not stretch",small,tall)
        val body=compose.onNodeWithTag("body").captureToImage().toPixelMap()
        assertEquals("fallback keeps white body",1f,body[body.width/2,body.height/2].red,.01f)
        assertEquals(1f,body[body.width/2,body.height/2].green,.01f)
        compose.runOnIdle { opacity.floatValue=0f }
        settle()
        val transparent=compose.onNodeWithTag("body").captureToImage().toPixelMap()
        assertEquals("unsupported/no-wallpaper fallback also honors opacity",Color.Black,transparent[transparent.width/2,transparent.height/2])
    }

    @Test fun nativeMaterialAndStickerShowcase() {
        checkIsolated()
        compose.setContent {
            MaterialTheme {
                val backdrop=rememberHazeState()
                CompositionLocalProvider(LocalBubbleBackdrop provides backdrop) {
                    ThemeMotionProvider(false) {
                        Box(Modifier.fillMaxSize()) {
                            Image(painterResource(me.rerere.rikkahub.R.drawable.market_cover_cafe),null,
                                Modifier.fillMaxSize().hazeSource(backdrop),contentScale=ContentScale.Crop)
                            Column(Modifier.fillMaxSize().padding(horizontal=22.dp,vertical=42.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
                                Text("气泡材质 · 原生预览",color=Color(0xFF243B50),fontSize=24.sp)
                                Text("毛玻璃",color=Color(0xFF243B50))
                                OrbisThemedBubble(false,ThemeBubble(bg="#FFF9F0",material=ThemeBubbleMaterial(blur=24f,tintOpacity=.5f)),Modifier.fillMaxWidth()) {
                                    Text("窗外的光晕开了，\n你的话还是清清楚楚。",fontSize=16.sp)
                                }
                                Text("液态玻璃",color=Color(0xFF243B50))
                                OrbisThemedBubble(true,ThemeBubble(bg="#F1FBFF",radius=28,material=ThemeBubbleMaterial(type="liquid",blur=3f,depth=.12f,tintOpacity=.2f,dispersion=.55f,highlight=.8f,rim=.7f)),Modifier.fillMaxWidth()) {
                                    Text("像一小片流动的水，\n把光收在边缘。",fontSize=16.sp)
                                }
                                Text("手绘贴纸",color=Color(0xFF243B50))
                                val bow=ThemeDrawing(layers=listOf(
                                    ThemeVectorLayer("M50 50 Q0 0 5 50 Q0 95 50 50 Q100 0 95 50 Q100 95 50 50 Z",fill="#D995AD",stroke="#955B77",strokeWidth=3f),
                                    ThemeVectorLayer("M42 39 L58 39 L58 61 L42 61 Z",fill="#F7D1DD",stroke="#955B77",strokeWidth=3f)))
                                OrbisThemedBubble(false,ThemeBubble(bg="#FFF4F7",radius=16,drawing=ThemeDrawing(stroke="#CE9BAE",strokeWidth=1.5f),stickers=listOf(ThemeSticker(drawing=bow,size=48),ThemeSticker(drawing=bow,size=32,anchor="bottomStart",rotation=-12f))),Modifier.fillMaxWidth()) {
                                    Text("给今天系一个蝴蝶结。\n贴纸的位置和图案都由你设计。",fontSize=16.sp)
                                }
                                Text("同一套真实气泡组件 · 示例内容",fontSize=12.sp,color=Color(0xFF435568))
                            }
                        }
                    }
                }
            }
        }
        capture("materials")
    }
}
