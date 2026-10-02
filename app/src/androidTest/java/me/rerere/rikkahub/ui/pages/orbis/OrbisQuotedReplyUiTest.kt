package me.rerere.rikkahub.ui.pages.orbis

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.OrbisMessageQuote
import me.rerere.rikkahub.data.favorite.OrbisFavoritePartKind
import me.rerere.rikkahub.data.favorite.OrbisFavoriteSnapshot
import me.rerere.rikkahub.data.favorite.OrbisFavoriteTextPart
import me.rerere.rikkahub.testutil.createShellComposeRule
import me.rerere.rikkahub.ui.components.message.OrbisQuotePreview
import me.rerere.rikkahub.ui.components.message.OrbisQuoteCard
import me.rerere.rikkahub.ui.pages.favorite.OrbisFavoriteDetail
import me.rerere.rikkahub.ui.pages.favorite.OrbisFavoriteDetailDialog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.uuid.Uuid

/** Synthetic shell only. No database, Koin, ChatService, network, or user's preferences. */
@RunWith(AndroidJUnit4::class)
class OrbisQuotedReplyUiTest {
    @get:Rule val compose = createShellComposeRule()
    private fun quote() = OrbisMessageQuote(Uuid.random(), Uuid.random(), Uuid.random(),
        MessageRole.ASSISTANT, "synthetic quote body", "2026-01-01T00:00")

    @Test fun cancelQuoteOnlyLeavesDraftUntouched() {
        val input = me.rerere.rikkahub.ui.hooks.ChatInputState().apply {
            setMessageText("synthetic unsent draft")
            orbisQuote = quote()
        }
        compose.setContent { MaterialTheme { input.orbisQuote?.let { OrbisQuotePreview(it) { input.orbisQuote = null } } } }
        compose.onNodeWithText("synthetic quote body").assertIsDisplayed()
        compose.onNodeWithText("取消引用").performClick()
        compose.onNodeWithText("synthetic quote body").assertDoesNotExist()
        compose.runOnIdle { assertEquals("synthetic unsent draft", input.textContent.text.toString()) }
    }

    @Test fun quoteCardOpensDetailAndJumpRequiresAnExplicitSecondTap() {
        var jumps = 0
        val saved = quote()
        compose.setContent { MaterialTheme { OrbisQuoteCard(saved) { jumps++ } } }
        assertEquals(0, jumps)
        compose.onNodeWithText("展开引用正文").assertDoesNotExist()
        compose.onNodeWithText("定位原消息").assertDoesNotExist()
        compose.onNodeWithTag("orbis-quote-card").assertHasClickAction().performClick()
        compose.onNodeWithText(saved.bodySnapshot).assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, jumps) }
        compose.onNodeWithText("定位原消息").performClick()
        compose.runOnIdle { assertEquals(1, jumps) }
        compose.onNodeWithText("定位原消息").assertDoesNotExist()
        compose.onNodeWithText("AI：synthetic quote body").assertIsDisplayed()
    }

    @Test fun closeDetailKeepsSnapshotAndDoesNotJumpOrExpandTheTimeline() {
        var jumps = 0
        val saved = quote().copy(bodySnapshot = "**完整引用**\n\n原文段落保留。")
        compose.setContent { MaterialTheme { OrbisQuoteCard(saved) { jumps++ } } }
        compose.onNodeWithText("AI：完整引用 原文段落保留。").assertIsDisplayed()
        compose.onNodeWithTag("orbis-quote-card").performClick()
        compose.onNodeWithText(saved.bodySnapshot).assertIsDisplayed()
        compose.onNodeWithText("关闭").performClick()
        compose.onNodeWithText(saved.bodySnapshot).assertDoesNotExist()
        compose.onNodeWithText("AI：完整引用 原文段落保留。").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(0, jumps)
            assertEquals("**完整引用**\n\n原文段落保留。", saved.bodySnapshot)
        }
    }

    @Test fun quoteWithoutJumpOnlyOpensSavedBodyAndExplainsUnavailableNavigation() {
        val saved = quote().copy(sourceRole = MessageRole.USER)
        compose.setContent { MaterialTheme { OrbisQuoteCard(saved) } }
        compose.onNodeWithText("自己：synthetic quote body").assertIsDisplayed()
        compose.onNodeWithTag("orbis-quote-card").performClick()
        compose.onNodeWithText(saved.bodySnapshot).assertIsDisplayed()
        compose.onNodeWithText("定位原消息").assertDoesNotExist()
        compose.onNodeWithText("这里保留的是引用快照，当前无法定位原消息。").assertIsDisplayed()
        compose.onNodeWithText("关闭").performClick()
        compose.onNodeWithTag("orbis-quote-card").assertIsDisplayed()
    }

    @Test fun longQuoteStaysTwoLinesAtLargeFontScaleAndDoesNotFillWideScreens() {
        val saved = quote().copy(bodySnapshot = "**标题**\n\n" + "很长的引用正文\n\t".repeat(500))
        compose.setContent { CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
            MaterialTheme { Box(Modifier.requiredSize(340.dp, 220.dp)) { OrbisQuoteCard(saved) } }
        } }
        val card = compose.onNodeWithTag("orbis-quote-card").assertIsDisplayed()
        // This fixture uses Density(1f), so the 300 dp bound is also 300 pixels.
        assertTrue(card.fetchSemanticsNode().boundsInRoot.width <= 300f)
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithTag("orbis-quote-preview-text", useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val layout = layouts.single()
        assertEquals(2, layout.lineCount)
        assertEquals(2, layout.layoutInput.maxLines)
        assertTrue(layout.isLineEllipsized(1))
        assertTrue(layout.layoutInput.text.text.startsWith("AI：标题 很长的引用正文"))
        assertTrue(!layout.layoutInput.text.text.contains('\n'))
        assertTrue(layout.size.height >= layout.getLineBottom(1))
        compose.onNodeWithText("定位原消息").assertDoesNotExist()
    }

    @Test fun compactQuoteBackgroundIsTranslucentInLightAndDarkThemes() {
        var dark by mutableStateOf(false)
        val saved = quote()
        val backdrop = Color(0xFF802040)
        compose.setContent { CompositionLocalProvider(LocalDensity provides Density(1f, 1f)) {
            MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                Box(Modifier.requiredSize(340.dp, 180.dp).background(backdrop)) { OrbisQuoteCard(saved) }
            }
        } }
        for (isDark in listOf(false, true)) {
            compose.runOnIdle { dark = isDark }
            val image = compose.onNodeWithTag("orbis-quote-card").captureToImage().toPixelMap()
            // Left padding at mid-height: inside the rounded shape, clear of the text.
            val actual = image[5, image.height / 2]
            val palette = if (isDark) darkColorScheme() else lightColorScheme()
            val expected = palette.surfaceContainerHigh.copy(alpha = 0.64f).compositeOver(backdrop)
            assertTrue(kotlin.math.abs(actual.red - expected.red) < 0.03f)
            assertTrue(kotlin.math.abs(actual.green - expected.green) < 0.03f)
            assertTrue(kotlin.math.abs(actual.blue - expected.blue) < 0.03f)
        }
    }

    @Test fun favoriteKeepsReasoningLocallyFoldedWithoutSourceJump() {
        var jumps = 0
        val saved = OrbisFavoriteSnapshot(Uuid.random(), Uuid.random(), Uuid.random(), MessageRole.ASSISTANT,
            "2026-01-01T00:00", listOf(OrbisFavoriteTextPart(OrbisFavoritePartKind.BODY, "saved body"),
                OrbisFavoriteTextPart(OrbisFavoritePartKind.REASONING, "saved reasoning")), unarchivedPartCount = 1)
        compose.setContent { MaterialTheme {
            OrbisFavoriteDetailDialog(OrbisFavoriteDetail(saved, false, ""), {}, { jumps++ })
        } }
        compose.onNodeWithText("saved body").assertIsDisplayed()
        compose.onNodeWithText("saved reasoning").assertDoesNotExist()
        compose.onNodeWithText("展开已保存的思考").performClick()
        compose.onNodeWithText("saved reasoning").assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, jumps) }
    }
}
