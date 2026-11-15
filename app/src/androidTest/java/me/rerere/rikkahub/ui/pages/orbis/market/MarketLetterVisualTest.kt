package me.rerere.rikkahub.ui.pages.orbis.market

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.serialization.json.*
import me.rerere.rikkahub.data.ai.IsolatedGenerationLoopRunner
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Offline synthetic visual fixtures use the production widgets, never a real market account. */
@RunWith(AndroidJUnit4::class)
class MarketLetterVisualTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val entries = listOf(
        entry("rain", "雨夜便利店", "星禾", "extra", "如果我们在一场大雨里第一次相遇。", 128),
        entry("cafe", "交换一天的生活", "晚枝", "grid", "换一个身份，重新认识彼此。", 96),
        entry("letter", "一封寄错的信", "清岚", "extra", "一封本不该送到你手里的信，却让我们开始了对话。", 82))
    private val wishes = listOf("想看一次\n笨拙的告白" to "不用盛大，只想认真说喜欢。", "如果我们是邻居" to "从借一把伞开始。", "一起经营旧书店" to "在书页与咖啡香里，慢慢靠近。", "一封来自未来的信" to "写给现在的你，或写给那个还会相遇的我们。")
    private fun entry(id: String, title: String, author: String, category: String, summary: String, favorites: Int) = buildJsonObject {
        put("id", id); put("title", title); put("author", author); put("category", category); put("summary", summary); put("favorite_count", favorites); put("version", 1); put("state", "published")
    }
    private fun capture(name: String) {
        compose.waitForIdle()
        val bitmap = compose.onNodeWithTag("market-letter-preview").captureToImage().asAndroidBitmap()
        val dir = File(instrumentation.targetContext.getExternalFilesDir(null), "market-letter-preview").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
    }
    private fun screen(page: String, scale: Float = 1f, body: @Composable ColumnScope.() -> Unit) {
        check(instrumentation is IsolatedGenerationLoopRunner)
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, scale)) {
                MarketLetterTheme { Column(Modifier.fillMaxSize().background(LetterPaper).letterGrain().safeDrawingPadding().testTag("market-letter-preview")) {
                    LetterHeading(page, {}, {})
                    Column(Modifier.weight(1f).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = body)
                    LetterNavigation(page, true) {}
                } }
            }
        }
    }
    @Test fun discoverUsesCompactCardsAndSeparateEditingAction() {
        var opened = 0; var edited = 0
        val showEditing = mutableStateOf(false)
        screen("discover") {
            LetterDivider("由人类写下设定，让故事在你们之间发生")
            LetterComposeActions(true, {}, {})
            LetterFilters("") {}
            entries.forEachIndexed { index, row -> LetterFeedCard(row, true, { opened++ }, if(showEditing.value && index == 0) ({ edited++ }) else null) }
        }
        capture("01-discover-synthetic")
        compose.runOnIdle { showEditing.value = true }
        compose.onNodeWithText("编辑草稿").performClick()
        compose.runOnIdle { assertEquals(1, edited); assertEquals(0, opened) }
        compose.onNodeWithText("雨夜便利店").performClick()
        compose.runOnIdle { assertEquals(1, opened) }
    }
    @Test fun wishesHaveReadableTwoColumnNotes() {
        screen("wishes") {
            LetterDivider("把想看的故事，留给愿意书写的人")
            wishes.chunked(2).forEachIndexed { r, pair -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                pair.forEachIndexed { c, (title, description) -> LetterWishNote(buildJsonObject {
                    put("title", title); put("description", description); put("slots_used", r + c); put("category", "extra")
                }, r * 2 + c, Modifier.weight(1f)) { Button({}, Modifier.fillMaxWidth()) { Text("领取创作", fontSize = 13.sp) } } }
            } }
            Button({}, Modifier.fillMaxWidth()) { Text("＋ 写个愿望") }
            LetterDivider("每个愿望最多由 3 位创作者领取")
        }
        capture("02-wishes-synthetic")
    }
    @Test fun detailKeepsOriginalTextSeparateFromCover() {
        screen("detail") {
            LetterCover(entries.first(), Modifier.fillMaxWidth().height(170.dp))
            LetterSheet(flowers = true) {
                Text("雨夜便利店", fontSize = 25.sp)
                Text("星禾　·　番外　·　原创保护", fontSize = 12.sp)
                HorizontalDivider()
                Text("✧ 故事设定", fontSize = 16.sp)
                Text("一场突如其来的雨，把我们留在同一家深夜便利店。窗外的城市停了下来，只有热饮还冒着白雾。\n\n请以我和你为主角，写下一段关于初次相遇的番外。保留我们的名字，让对白自然，结尾留一点余温。", fontSize = 16.sp, lineHeight = 27.sp)
                Text("作者版本 1.0", fontSize = 11.sp)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { OutlinedButton({}, Modifier.weight(1f)) { Text("♡ 收藏") }; Button({}, Modifier.weight(1.4f)) { Text("发送给 AI") } }
        }
        capture("03-detail-synthetic")
    }
    @Test fun largeTextStillExposesTitleAndEditorWithoutCardClickLeak() {
        screen("mine", 1.5f) {
            LazyColumn { item { LetterFeedCard(entries[1], true, {}, {}) }; item { LetterWishNote(buildJsonObject { put("title", "一起经营旧书店"); put("description", "在书页与咖啡香里，慢慢靠近。"); put("slots_used", 0) }, 0) { Button({}) { Text("领取创作") } } } }
        }
        compose.onNodeWithText("交换一天的生活").assertIsDisplayed()
        compose.onNodeWithText("编辑草稿").assertIsDisplayed()
        capture("04-large-text-synthetic")
    }
}
