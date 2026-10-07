package me.rerere.rikkahub.ui.pages.orbis

import android.app.Application
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import me.rerere.rikkahub.data.ai.IsolatedGenerationLoopRunner
import me.rerere.rikkahub.data.orbis.OrbisAtlasDisplayMode
import me.rerere.rikkahub.data.orbis.OrbisAtlasMilkyWay
import me.rerere.rikkahub.data.orbis.OrbisMemoryAtlas as Atlas
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import java.io.File
import java.util.Locale

/** In-memory UI only, isolated Application: never starts Koin or opens a real user store. */
@RunWith(AndroidJUnit4::class)
class OrbisVisualRefreshDeviceTest {
    private val compose = createAndroidComposeRule<ComponentActivity>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val isolated = object : ExternalResource() {
        override fun before() {
            check(instrumentation is IsolatedGenerationLoopRunner)
            assertEquals(Application::class.java, instrumentation.targetContext.applicationContext.javaClass)
        }
    }
    @get:Rule val rules: RuleChain = RuleChain.outerRule(isolated).around(compose)

    @Test fun collapsedNavigationKeepsWorksVisibleAndFilterUntilExplicitClear() {
        val expanded = mutableStateOf(false)
        val filtered = mutableStateOf(true)
        compose.setContent { MaterialTheme { Column {
            GalleryNavigationPanel(expanded.value, { expanded.value = !expanded.value }, filtered.value, { filtered.value = false }) {
                Text("Synthetic private controls")
            }
            Text("Synthetic artwork")
        } } }
        compose.onNodeWithText("Synthetic private controls").assertDoesNotExist()
        compose.onNodeWithText("Synthetic artwork").assertIsDisplayed()
        compose.onNodeWithText("搜索、筛选与设置  ▾").performClick()
        compose.onNodeWithText("Synthetic private controls").assertIsDisplayed()
        compose.onNodeWithText("收起导航与设置  ▴").performClick()
        compose.onNodeWithText("Synthetic private controls").assertDoesNotExist()
        compose.runOnIdle { assertTrue(filtered.value) }
        compose.onNodeWithText("清除筛选").performClick()
        compose.runOnIdle { assertFalse(filtered.value) }
    }

    @Test fun galaxyPointStillOpensTheExactRealMemoryAndReusesItsFrame() {
        val graph = Atlas.demo()
        val layout = OrbisAtlasMilkyWay.layout(graph)
        val cache = MilkyWayFrameCache(layout)
        val view = Atlas.Camera()
        val target = cache.frame(view, 320.0, 420.0).memories.first()
        assertSame(cache.frame(view, 320.0, 420.0), cache.frame(view, 320.0, 420.0))
        val chosen = mutableStateOf<Int?>(null)
        compose.setContent { MaterialTheme {
            OrbisMemoryAtlasCanvas(graph, { view }, { 0.0 }, chosen.value, "银河静览", false,
                {}, { chosen.value = it }, {}, {}, {}, Modifier.size(320.dp, 420.dp).testTag("synthetic-galaxy"), OrbisAtlasDisplayMode.GALAXY)
        } }
        val density = instrumentation.targetContext.resources.displayMetrics.density
        compose.onNodeWithTag("synthetic-galaxy").performTouchInput {
            click(Offset((target.x * density).toFloat(), (target.y * density).toFloat()))
        }
        compose.runOnIdle { assertEquals(target.index, chosen.value) }
    }

    @Test fun captureFourSeasonLightAndDarkSyntheticPreviews() {
        val selectedSeason = mutableStateOf(OrbisSeason.SPRING)
        val dark = mutableStateOf(false)
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(
                    LocalOrbisSeason provides selectedSeason.value,
                    LocalOrbisDeepSeekStyle provides false,
                ) {
                    OrbisVisualTheme(darkTheme = dark.value) {
                        val season = selectedSeason.value
                        val colors = OrbisTheme.colors
                        Box(Modifier.fillMaxSize().systemBarsPadding().testTag("synthetic-preview-root")) {
                            OrbisSeasonWallpaper(season, dark.value, Modifier.fillMaxSize())
                            // Fixed phase: the production drawing, without any clock or observer.
                            Canvas(Modifier.fillMaxSize()) {
                                drawSeasonFloat(season, .23f, colors.star)
                            }
                            Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text(season.title, style = MaterialTheme.typography.headlineMedium, color = colors.ink)
                                Text("${if (dark.value) "深色" else "浅色"} · 四季外观合成预览", color = colors.mutedInk,
                                    style = MaterialTheme.typography.bodySmall)
                                Spacer(Modifier.height(24.dp))
                                Surface(color = colors.tintedPanel, contentColor = colors.ink, shape = RoundedCornerShape(22.dp)) {
                                    Text("把今天的小片段，留在我们的星空里。", Modifier.padding(18.dp),
                                        style = MaterialTheme.typography.bodyLarge)
                                }
                                Spacer(Modifier.weight(1f))
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OrbisDecorativeEntryCard(OrbisEntryArtwork.ARCADE, "游戏机", "一起玩一会儿", {},
                                        Modifier.weight(1f), compact = true)
                                    OrbisDecorativeEntryCard(OrbisEntryArtwork.SEALED_LETTER, "秘密基地", "写给彼此的信", {},
                                        Modifier.weight(1f), compact = true)
                                    OrbisDecorativeEntryCard(OrbisEntryArtwork.PRIVATE_ROOM, "隐私室", "保留自己的空间", {},
                                        Modifier.weight(1f), compact = true)
                                }
                                Text("仅合成内容 · 未读取聊天、壁纸或个人设置", color = colors.mutedInk,
                                    style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }
        }
        for (isDark in listOf(false, true)) {
            for (season in listOf(OrbisSeason.SPRING, OrbisSeason.SUMMER, OrbisSeason.AUTUMN, OrbisSeason.WINTER)) {
                compose.runOnIdle {
                    selectedSeason.value = season
                    dark.value = isDark
                }
                captureSyntheticPreview("season-${season.name.lowercase(Locale.ROOT)}-${if (isDark) "dark" else "light"}")
            }
        }
    }

    @Test fun captureLightweightAndGalaxySyntheticPreviews() {
        val graph = Atlas.demo()
        assertTrue(graph.isDemo)
        val view = Atlas.Camera()
        val mode = mutableStateOf(OrbisAtlasDisplayMode.LIGHTWEIGHT)
        compose.setContent {
            MaterialTheme {
                Column(Modifier.fillMaxSize().systemBarsPadding().background(Color(0xFF080B15)).testTag("synthetic-preview-root")) {
                    Column(Modifier.padding(horizontal = 24.dp, vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("记忆星盘 · ${if (mode.value == OrbisAtlasDisplayMode.GALAXY) "银河" else "轻量"}",
                            color = Color(0xFFE8D7B6), style = MaterialTheme.typography.headlineSmall)
                        Text("本地演示 · ${graph.stars.size} 个合成记忆星点", color = Color(0xFFB5B5C6),
                            style = MaterialTheme.typography.bodySmall)
                    }
                    OrbisMemoryAtlasCanvas(graph, { view }, { 0.0 }, null, "合成静览", false,
                        {}, {}, {}, {}, {}, Modifier.fillMaxWidth().weight(1f), mode.value)
                    Text("星云与微光是装饰；没有读取真实 ST 记忆。", Modifier.padding(24.dp),
                        color = Color(0xFFB5B5C6), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        for (displayMode in listOf(OrbisAtlasDisplayMode.LIGHTWEIGHT, OrbisAtlasDisplayMode.GALAXY)) {
            compose.runOnIdle { mode.value = displayMode }
            captureSyntheticPreview("atlas-${displayMode.storedValue}")
        }
    }

    /** Only the synthetic Compose node is captured, never the device screen or another app. */
    private fun captureSyntheticPreview(name: String) {
        require(name.matches(Regex("(?:season-(?:spring|summer|autumn|winter)-(?:light|dark)|atlas-(?:lightweight|galaxy))")))
        compose.waitForIdle()
        val bitmap = compose.onNodeWithTag("synthetic-preview-root").captureToImage().asAndroidBitmap()
        val context = instrumentation.targetContext
        val base = (context.getExternalFilesDir(null) ?: context.cacheDir).canonicalFile
        val directory = File(base, "1005-previews")
        check(directory.canonicalFile.parentFile == base)
        check(directory.isDirectory || directory.mkdirs())
        val target = File(directory, "$name.png")
        check(target.canonicalFile == target.absoluteFile)
        try {
            target.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            check(target.length() > 0L)
            println("ORBIS_SYNTHETIC_PREVIEW=${target.absolutePath}")
        } finally {
            bitmap.recycle()
        }
    }
}
