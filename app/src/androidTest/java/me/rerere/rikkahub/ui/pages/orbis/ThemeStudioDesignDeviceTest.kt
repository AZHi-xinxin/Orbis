package me.rerere.rikkahub.ui.pages.orbis

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.serialization.encodeToString
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.OrbisAppearance
import me.rerere.rikkahub.data.orbis.theme.*
import me.rerere.rikkahub.testutil.createShellComposeRule
import me.rerere.rikkahub.ui.components.message.*
import me.rerere.rikkahub.ui.context.LocalSettings
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ThemeStudioDesignDeviceTest {
    @get:Rule val compose=createShellComposeRule()

    @Test fun clickingADraftPreviewsThenPublishesWithoutChangingTheCurrentTheme() {
        val root=File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,"theme-draft-${UUID.randomUUID()}").canonicalFile
        try {
            val store=ThemeRepository(root.resolve("orbis-themes/owner"))
            val draft=store.write("""{"name":"待发布草稿"}""")
            compose.setContent { MaterialTheme { Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) { ThemePackageManagerContent(store) } } }
            compose.waitUntil(5000) { compose.onAllNodes(hasTestTag("theme-tile-${draft.id}")).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("theme-tile-${draft.id}").performScrollTo().performClick()
            compose.onNodeWithText("草稿 · 待发布草稿").assertExists()
            assertTrue(store.snapshot().entries.single().versions.isEmpty())
            compose.onNodeWithText("发布并预览").performClick()
            compose.waitUntil(5000) { store.snapshot().entries.single().versions.size==1 }
            compose.onNodeWithText("应用这个主题").assertExists()
            assertNull(store.snapshot().active)
            compose.onNodeWithText("应用这个主题").performClick()
            compose.waitUntil(5000) { store.snapshot().active?.id==draft.id }
        } finally { root.deleteRecursively() }
    }

    @Test fun sixCompactTilesPaginateAndDeletionClampsTheLastPage() {
        val root=File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,"theme-pages-${UUID.randomUUID()}").canonicalFile
        try {
            val store=ThemeRepository(root.resolve("orbis-themes/owner"))
            val entries=(1..7).map { i ->
                val base=if(i==1)themeDrawingExample() else ThemePackage(name="主题 $i",bubbles=ThemeBubbles(assistant=ThemeBubble(shape=listOf("cat","rabbit","round")[i%3])))
                val d=store.write(themeJson.encodeToString(base));store.publish(d.id,d.revision)
            }
            compose.setContent { MaterialTheme { Column(Modifier.width(320.dp).fillMaxHeight().background(Color(0xFFF1F6FA))
                .safeDrawingPadding().padding(12.dp).verticalScroll(rememberScrollState())) { ThemePackageManagerContent(store) } } }
            compose.waitUntil(5000) { compose.onAllNodes(hasTestTag("theme-tile-${entries[0].id}")).fetchSemanticsNodes().isNotEmpty() }
            entries.take(6).forEach { compose.onNodeWithTag("theme-tile-${it.id}").assertExists() }
            compose.onNodeWithTag("theme-tile-${entries.last().id}").assertDoesNotExist()
            val firstTop=compose.onNodeWithTag("theme-tile-${entries[0].id}").fetchSemanticsNode().boundsInRoot.top
            val thirdTop=compose.onNodeWithTag("theme-tile-${entries[2].id}").fetchSemanticsNode().boundsInRoot.top
            assertEquals("three columns at 320dp",firstTop,thirdTop,1f)
            save("theme-studio-dev126.png")
            compose.onNodeWithTag("theme-page-next").performScrollTo().performClick()
            compose.onNodeWithTag("theme-page-number").assertTextEquals("2 / 2")
            compose.onNodeWithTag("theme-tile-${entries[0].id}").assertDoesNotExist()
            compose.onNodeWithTag("theme-tile-${entries.last().id}").assertExists()
            compose.onNodeWithText("预览主题").performScrollTo().performClick()
            compose.onNodeWithText("应用这个主题").performClick()
            compose.waitUntil(5000) { store.snapshot().active?.id==entries.last().id }
            compose.onNodeWithText("删除所选").performScrollTo().performClick()
            compose.waitUntil(5000) { store.snapshot().entries.size==6 }
            compose.onNodeWithTag("theme-page-number").performScrollTo().assertTextEquals("1 / 1")
            compose.onNodeWithTag("theme-page-next").assertIsNotEnabled()
        } finally { root.deleteRecursively() }
    }

    @Test fun freePathLayerMovesAtAuthoredKeyframesWhileTextStyleStaysIndependent() {
        val clock=mutableLongStateOf(0)
        val drawing=ThemeDrawing(outline="M 0 0 L 100 0 L 100 100 L 0 100 Z",layers=listOf(
            ThemeVectorLayer("M 0 0 L 20 0 L 20 100 L 0 100 Z",fill="#D02020",
                motion=ThemeMotion(durationMs=1000,frames=listOf(ThemeKeyframe(0f),ThemeKeyframe(1f,x=60f))))))
        val a=ThemeBubble(bg="#FFFFFF",radius=0,maxWidthRatio=1f,drawing=drawing,typography=ThemeTypography(weight=700,italic=true,letterSpacing=.08f))
        val u=ThemeBubble(typography=ThemeTypography(fontFamily="monospace",weight=300))
        var assistantStyle:TextStyle?=null;var userStyle:TextStyle?=null
        compose.setContent { MaterialTheme {
            CompositionLocalProvider(LocalSettings provides Settings(),LocalThemeMotionClock provides clock,
                LocalChatTheme provides ChatTheme(ThemePackage(name="custom",bubbles=ThemeBubbles(a,u)),null)) {
                assistantStyle=rememberChatMessageTextStyle(false);userStyle=rememberChatMessageTextStyle(true)
                OrbisMessageBubble(false,OrbisAppearance(),Modifier.width(240.dp).testTag("motion")) { Spacer(Modifier.fillMaxWidth().height(48.dp)) }
            }
        } }
        fun pixel(x:Float)=compose.onNodeWithTag("motion").captureToImage().toPixelMap().let { it[(it.width*x).toInt(),it.height/2] }
        assertTrue(pixel(.1f).green<.2f);assertTrue(pixel(.7f).green>.9f)
        compose.runOnIdle {clock.longValue=1000}
        assertTrue(pixel(.1f).green>.9f);assertTrue(pixel(.7f).green<.2f)
        assertEquals(FontWeight(700),assistantStyle!!.fontWeight);assertEquals(FontStyle.Italic,assistantStyle!!.fontStyle)
        assertEquals(.08f,assistantStyle!!.letterSpacing.value,.001f);assertEquals(FontWeight(300),userStyle!!.fontWeight)
        assertEquals(FontStyle.Normal,userStyle!!.fontStyle)
    }

    @Test fun realRendererShowsFreeDrawingAndTypography() {
        compose.setContent { MaterialTheme { Column(Modifier.fillMaxSize().background(Color(0xFFE5EEF3)).padding(20.dp),
            verticalArrangement=Arrangement.spacedBy(16.dp)) {
            Text("自己画的气泡 · 自己写的动画")
            ThemeChatPreview(themeDrawingExample())
            Text("同一个主题里微调，不增加副本")
        } } }
        save("theme-free-drawing-dev126.png")
    }
    private fun save(name:String) {
        val file=File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null),name)
        file.outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG,100,it) }
    }
}
