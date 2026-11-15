package me.rerere.rikkahub.ui.pages.orbis

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.uuid.Uuid
import me.rerere.rikkahub.data.ai.IsolatedGenerationLoopRunner
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.OrbisAppearance
import me.rerere.rikkahub.data.orbis.theme.*
import me.rerere.rikkahub.ui.components.message.LocalBubbleBackdrop
import me.rerere.rikkahub.ui.theme.LocalDarkMode
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class CommunityAppearanceDeviceTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    @Test fun drawerLoadsAssistantWallpaperMaterialAndComposerInk() {
        check(InstrumentationRegistry.getInstrumentation() is IsolatedGenerationLoopRunner)
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val owner=Uuid.random();val store=openThemes(context,owner.toString())
        val root=File(context.filesDir,"orbis-themes/$owner")
        try {
            val bitmap=android.graphics.Bitmap.createBitmap(32,32,android.graphics.Bitmap.Config.ARGB_8888).apply {eraseColor(android.graphics.Color.MAGENTA)}
            val bytes=ByteArrayOutputStream().use {bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it);it.toByteArray()};bitmap.recycle()
            val asset=importThemeImage(store,bytes)
            val ref=store.prepareMaterialPreset(MaterialThemePreset.LIQUID_STILL,asset);store.confirm(ref)
            store.setMaterialTextColor(ref,MaterialTextTarget.CHROME,"#142638")
            val settings=Settings(assistantId=owner,assistants=listOf(Assistant(id=owner)))
            var seen:ChatTheme?=null;var optical=false
            compose.setContent { MaterialTheme { GardenQuickChatTheme(settings,owner) {
                val theme=LocalChatTheme.current
                Box(Modifier.size(320.dp).testTag("resolved-wallpaper")) {
                    OrbisCommunityBackdrop(settings,true) {
                        val source=LocalBubbleBackdrop.current
                        SideEffect {seen=theme;optical=source!=null}
                        if(theme!=null) GardenQuickChatInput("drawer ink",{},true)
                    }
                }
            } } }
            compose.waitUntil(5000) {seen?.body?.background?.asset==asset && optical}
            assertEquals(store.asset(asset).absolutePath,seen!!.image)
            assertNotNull(seen!!.body.chrome.composerSurface?.material)
            assertEquals("#142638",seen!!.body.chrome.onComposer)
            compose.waitUntil(5000) {
                val pixels=compose.onNodeWithTag("resolved-wallpaper").captureToImage().toPixelMap()
                val c=pixels[pixels.width/2,pixels.height/2]
                c.red>c.green+.3f && c.blue>c.green+.3f
            }
            val layouts=mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
            compose.onNodeWithText("drawer ink").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) {it(layouts)}
            assertEquals(Color(0xFF142638),layouts.single().layoutInput.style.color)
        } finally {root.deleteRecursively()}
    }
    @Test fun nightSettingsRemainDarkWithPaleGlassThemeAndDoNotChangeChatPackage() {
        var dark by mutableStateOf(true)
        val body=materialThemePreset(MaterialThemePreset.LIQUID_STILL,"0".repeat(64)+".png")
        val theme=ChatTheme(body,null,followHumanAccent=true)
        var ink=Color.Unspecified;var chatTheme:ChatTheme?=null
        compose.setContent { MaterialTheme {
            CompositionLocalProvider(LocalDarkMode provides dark,LocalChatTheme provides theme) {
                Column {
                    OrbisInterfaceTheme {
                        val colors=OrbisTheme.colors
                        SideEffect {ink=colors.ink}
                        Box(Modifier.size(160.dp).background(colors.page).testTag("settings-surface"))
                    }
                    val inherited=LocalChatTheme.current
                    SideEffect {chatTheme=inherited}
                }
            }
        } }
        fun surface()=compose.onNodeWithTag("settings-surface").captureToImage().toPixelMap().let {it[10,10]}
        val night=surface();assertTrue(night.red<.2f && night.green<.2f && night.blue<.25f)
        assertTrue(ink.red>.6f);assertEquals(theme,chatTheme)
        compose.runOnIdle {dark=false}
        val day=surface();assertTrue(day.red>.8f && day.green>.8f && day.blue>.8f)
        assertTrue(ink.red<.5f);assertEquals(theme,chatTheme)
    }
}
