package me.rerere.rikkahub.ui.pages.orbis

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import me.rerere.rikkahub.data.ai.IsolatedGenerationLoopRunner
import me.rerere.rikkahub.data.model.OrbisAppearance
import me.rerere.rikkahub.data.orbis.theme.*
import me.rerere.rikkahub.ui.components.message.*
import me.rerere.rikkahub.ui.components.ai.chatInputFieldColors
import me.rerere.rikkahub.ui.components.ai.SendButton
import me.rerere.rikkahub.ui.context.LocalOrbisChatTextColor
import me.rerere.rikkahub.ui.theme.LocalDarkMode
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID
import kotlin.math.abs

class MaterialPresetDeviceTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private fun isolated() {check(InstrumentationRegistry.getInstrumentation() is IsolatedGenerationLoopRunner);check(context.packageName.endsWith(".bodylab"))}
    @Test fun builtinsArePreviewedAppliedAndExposeTheBackgroundPicker() {
        isolated();val root=File(context.cacheDir,"preset-manager-${UUID.randomUUID()}").canonicalFile
        try {
            val store=ThemeRepository(root.resolve("orbis-themes/owner"))
            compose.setContent { MaterialTheme { Column(Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp).verticalScroll(rememberScrollState())) {ThemePackageManagerContent(store)} } }
            compose.onNodeWithTag("material-wallpaper").assertIsNotEnabled()
            for(preset in MaterialThemePreset.entries) {
                compose.onNodeWithTag("material-preset-${preset.key}").performScrollTo().performClick()
                compose.waitUntil(5000) {compose.onAllNodesWithText("应用这个主题").fetchSemanticsNodes().isNotEmpty()}
                compose.onNodeWithText("应用这个主题").performClick()
                compose.waitUntil(5000) {store.snapshot().active?.id==preset.id}
                compose.onNodeWithTag("material-wallpaper").assertIsEnabled()
                assertNotNull(store.snapshot().resolve()!!.bubbles.assistant.material)
            }
            compose.onNodeWithTag("material-wallpaper-reset").performScrollTo().performClick()
            compose.waitUntil(5000) {store.snapshot().active!!.revision==2}
            assertEquals("ice",store.snapshot().resolve()!!.bubbles.assistant.texture!!.type)
        } finally {root.deleteRecursively()}
    }
    @Test fun persistedPresetRunsThroughTheRealProviderAndLifecycleClock() {
        isolated();val owner="preset-live-${UUID.randomUUID()}";val store=openThemes(context,owner)
        val root=File(context.filesDir,"orbis-themes/$owner")
        try {
            val image=importThemeImage(store,context.resources.openRawResource(me.rerere.rikkahub.R.drawable.market_cover_cafe).use {it.readBytes()})
            val ref=store.prepareMaterialPreset(MaterialThemePreset.LIQUID,image);store.confirm(ref)
            var tick: State<Long>?=null
            compose.setContent {
                MaterialTheme {
                    AssistantThemeProvider(owner) {
                        val theme=LocalChatTheme.current
                        val source=rememberHazeState();val otherSource=rememberHazeState()
                        val sharedClock=LocalThemeMotionClock.current
                        SideEffect {tick=sharedClock}
                        CompositionLocalProvider(LocalBubbleBackdrop provides source) {
                            Box(Modifier.fillMaxSize()) {
                                OrbisChatBackdrop(OrbisAppearance(),Modifier.fillMaxSize().hazeSource(otherSource).hazeSource(source))
                                if(theme!=null) OrbisThemedBubble(false,theme.body.bubbles.assistant,
                                    Modifier.align(Alignment.Center).width(290.dp).testTag("live-material")) {
                                    Box(Modifier.fillMaxWidth().height(130.dp)) {
                                        Box(Modifier.align(Alignment.Center).size(18.dp).background(Color.Blue).testTag("live-ink"))
                                    }
                                }
                            }
                        }
                    }
                }
            }
            compose.waitUntil(5000) {tick?.value?.let {it>0}==true}
            val first=compose.onNodeWithTag("live-material").captureToImage().toPixelMap()
            val start=tick!!.value
            compose.waitUntil(5000) {tick!!.value>start+1200}
            val second=compose.onNodeWithTag("live-material").captureToImage().toPixelMap()
            var delta=0.0;var n=0
            for(y in first.height/4 until first.height*3/4) for(x in 5 until first.width/5) {delta+=abs(first[x,y].red-second[x,y].red);n++}
            assertTrue("persisted theme actually moves wallpaper: ${delta/n}",delta/n>.003)
            val ink=compose.onNodeWithTag("live-ink").captureToImage().toPixelMap();assertEquals(Color.Blue,ink[ink.width/2,ink.height/2])
        } finally {root.deleteRecursively()}
    }
    @Test fun nativeColorEditorSavesEachTargetAndCancelDoesNotWrite() {
        isolated();val root=File(context.cacheDir,"preset-colors-${UUID.randomUUID()}").canonicalFile
        try {
            val store=ThemeRepository(root.resolve("orbis-themes/owner"))
            val image=importThemeImage(store,context.resources.openRawResource(me.rerere.rikkahub.R.drawable.market_cover_cafe).use {it.readBytes()})
            val ref=store.prepareMaterialPreset(MaterialThemePreset.LIQUID,image);store.confirm(ref)
            compose.setContent { MaterialTheme { Column(Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp).verticalScroll(rememberScrollState())) {ThemePackageManagerContent(store)} } }
            MaterialTextTarget.entries.forEach { target ->
                compose.onNodeWithTag("material-text-${target.name.lowercase()}").performScrollTo().performClick()
                compose.onNodeWithTag("chat-text-color-preset-白色").performScrollTo().performClick()
                assertNull(store.snapshot().materialTextColors[ref.id]?.get(target)) // Preview only until save.
                compose.onNodeWithTag("material-text-save").performClick()
                compose.waitUntil(5000) {store.snapshot().materialTextColors[ref.id]?.get(target)=="#FFFFFF"}
            }
            compose.onNodeWithTag("material-text-user").performScrollTo().performClick()
            compose.onNodeWithTag("chat-text-color-preset-黑色").performScrollTo().performClick()
            compose.onNodeWithText("取消").performClick()
            assertEquals("#FFFFFF",store.snapshot().materialTextColors[ref.id]!!.user)
            compose.onNodeWithTag("material-text-user").performScrollTo().performClick()
            compose.onNodeWithText("恢复默认").performScrollTo().performClick()
            compose.onNodeWithTag("material-text-save").performClick()
            compose.waitUntil(5000) {store.snapshot().materialTextColors[ref.id]?.user==null}
            assertEquals("#FFFFFF",store.snapshot().displayBody()!!.chrome.onComposer)
        } finally {root.deleteRecursively()}
    }
    @Test fun savedColorsReachBothNativeBubbleForegroundsAndTheRealComposerColors() {
        isolated();val owner="preset-ink-${UUID.randomUUID()}";val store=openThemes(context,owner)
        val root=File(context.filesDir,"orbis-themes/$owner")
        try {
            val image=importThemeImage(store,context.resources.openRawResource(me.rerere.rikkahub.R.drawable.market_cover_cafe).use {it.readBytes()})
            val ref=store.prepareMaterialPreset(MaterialThemePreset.FROSTED,image);store.confirm(ref)
            var assistantInk:Color?=null;var userInk:Color?=null;var composerInk:Color?=null;var dockInk:String?=null
            compose.setContent { MaterialTheme { AssistantThemeProvider(owner) {
                val body=LocalChatTheme.current?.body
                if(body!=null) Column {
                    OrbisThemedBubble(false,body.bubbles.assistant,Modifier) {
                        val ink=LocalOrbisChatTextColor.current;val contentInk=LocalContentColor.current
                        SideEffect {assertEquals(ink,contentInk);assistantInk=ink};Text("对方文字")
                    }
                    OrbisThemedBubble(true,body.bubbles.user,Modifier) {
                        val ink=LocalOrbisChatTextColor.current
                        SideEffect {userInk=ink};Text("我的文字")
                    }
                    val input=chatInputFieldColors()
                    SideEffect {composerInk=input.focusedTextColor;dockInk=body.chrome.onDock}
                }
            } } }
            compose.waitUntil(5000) {assistantInk!=null}
            store.setMaterialTextColor(ref,MaterialTextTarget.ASSISTANT,"#FFFFFF")
            store.setMaterialTextColor(ref,MaterialTextTarget.USER,"#FFE0F0")
            store.setMaterialTextColor(ref,MaterialTextTarget.CHROME,"#DBEEFF")
            compose.waitUntil(5000) {assistantInk==Color.White && userInk==Color(0xFFFFE0F0) && composerInk==Color(0xFFDBEEFF) && dockInk=="#DBEEFF"}
        } finally {root.deleteRecursively()}
    }
    @Test fun materialStarAndRealSendButtonFollowSeasonChanges() {
        isolated();val owner="preset-season-${UUID.randomUUID()}";val store=openThemes(context,owner)
        val root=File(context.filesDir,"orbis-themes/$owner")
        try {
            val image=importThemeImage(store,context.resources.openRawResource(me.rerere.rikkahub.R.drawable.market_cover_cafe).use {it.readBytes()})
            store.confirm(store.prepareMaterialPreset(MaterialThemePreset.LIQUID,image))
            var season by mutableStateOf(OrbisSeason.AUTUMN)
            var observed:OrbisColors?=null
            var inputInk:Color?=null
            var sends=0;var homes=0
            compose.setContent {
                CompositionLocalProvider(LocalDarkMode provides false,LocalOrbisSeason provides season) {
                    MaterialTheme { OrbisVisualTheme(darkTheme=false) { AssistantThemeProvider(owner) {
                        val colors=OrbisTheme.colors
                        val input=chatInputFieldColors()
                        SideEffect {observed=colors;inputInk=input.focusedTextColor}
                        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                            SendButton(false,false,{sends++},{})
                            OrbisBottomDock("主题",{homes++},{},windowInsets=WindowInsets(0,0,0,0))
                        }
                    } } }
                }
            }
            for (value in listOf(OrbisSeason.AUTUMN,OrbisSeason.SPRING,OrbisSeason.WINTER)) {
                compose.runOnIdle {season=value}
                val expected=orbisSeasonColors(value,false)
                compose.waitUntil(5000) {observed?.accent==expected.accent && observed?.star==expected.star}
                assertEquals(expected.ink,inputInk)
                assertEquals(expected.ink,observed!!.ink)
                assertEquals(expected.mutedInk,observed!!.mutedInk)
                assertEquals(expected.onDock,observed!!.onDock)
                val send=compose.onNodeWithTag("chat_send_button").captureToImage().toPixelMap()
                val star=compose.onNodeWithContentDescription("返回 Orbis 主页").captureToImage().toPixelMap()
                assertEquals(expected.accent,send[send.width/2,send.height/4])
                assertEquals(expected.star,star[star.width/2,star.height/2])
            }
            compose.onNodeWithTag("chat_send_button").performClick()
            compose.onNodeWithContentDescription("返回 Orbis 主页").performClick()
            assertEquals(1,sends);assertEquals(1,homes)
        } finally {root.deleteRecursively()}
    }
    @Test fun staticPresetKeepsTheOpticalSurfaceWithoutRunningAMotionClock() {
        isolated();val owner="preset-still-${UUID.randomUUID()}";val store=openThemes(context,owner)
        val root=File(context.filesDir,"orbis-themes/$owner")
        try {
            val image=importThemeImage(store,context.resources.openRawResource(me.rerere.rikkahub.R.drawable.market_cover_cafe).use {it.readBytes()})
            store.confirm(store.prepareMaterialPreset(MaterialThemePreset.LIQUID_STILL,image))
            var loaded=false;var hasClock=true
            compose.setContent {MaterialTheme {AssistantThemeProvider(owner) {
                val body=LocalChatTheme.current?.body
                val motion=LocalThemeMotionClock.current
                val source=rememberHazeState()
                if(body!=null) {
                    SideEffect {loaded=true;hasClock=motion!=null}
                    CompositionLocalProvider(LocalBubbleBackdrop provides source) {
                        Box(Modifier.fillMaxSize()) {
                            Box(Modifier.fillMaxSize().hazeSource(source).background(Color(0xFF183249)))
                            OrbisThemedBubble(false,body.bubbles.assistant,Modifier.align(Alignment.Center).width(290.dp).testTag("still-optics")) {
                                Box(Modifier.fillMaxWidth().height(160.dp))
                            }
                        }
                    }
                }
            } } }
            compose.waitUntil(5000) {loaded};assertFalse(hasClock)
            compose.waitForIdle();Thread.sleep(300)
            val first=compose.onNodeWithTag("still-optics").captureToImage().toPixelMap()
            Thread.sleep(700)
            val second=compose.onNodeWithTag("still-optics").captureToImage().toPixelMap()
            assertTrue("tinted optical surface is visible",first[first.width/2,first.height/2].red>35f/255f)
            var delta=0f
            for(y in 0 until first.height step 3) for(x in 0 until first.width step 3) {
                delta=maxOf(delta,abs(first[x,y].red-second[x,y].red))
            }
            assertTrue("static edges must not move: $delta",delta<.005f)
        } finally {root.deleteRecursively()}
    }
}
