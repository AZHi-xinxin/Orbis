package me.rerere.rikkahub.ui.pages.orbis

import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import me.rerere.rikkahub.data.orbis.OrbisGameRepository
import me.rerere.rikkahub.data.orbis.OrbisGameStorage
import me.rerere.rikkahub.data.orbis.OrbisMiniGameRepository
import me.rerere.rikkahub.testutil.createShellComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/** Measured synthetic viewports only; never rotates the real phone or touches app storage/models. */
@RunWith(AndroidJUnit4::class)
class OrbisGameLayoutUiTest {
    @get:Rule val compose = createShellComposeRule()

    private class MemoryStorage : OrbisGameStorage {
        private var text: String? = null
        var failWrites = false
        override fun read() = text
        override fun write(value: String) {
            check(!failWrites) { "Synthetic failed write" }
            text = value
        }
    }

    private class Fixture {
        val storage = MemoryStorage()
        val repository = OrbisGameRepository(storage)
        val library = OrbisMiniGameRepository(MemoryStorage())
        val match = repository.start()
    }

    private fun show(fixture: Fixture, width: Float, height: Float, fontScale: Float = 1f, openNative: Boolean = true) {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, fontScale)) {
                MaterialTheme { OrbisVisualTheme(darkTheme = false) {
                    Box(Modifier.size(width.dp, height.dp)) {
                        GameContent(fixture.repository, fixture.library, null, "合成助手",
                            { error("Layout fixtures must not call a model") }, {})
                    }
                } }
            }
        }
        if (openNative) {
            compose.onNodeWithTag("orbis-game-list").performScrollToNode(hasText("继续本局"))
            compose.onNodeWithText("继续本局").performClick()
        }
        compose.waitForIdle()
    }

    private fun assertBoardFitsSquare(): Pair<Float, Float> {
        compose.onNodeWithTag("orbis-game-list").performScrollToNode(hasTestTag("orbis-gomoku-board"))
        compose.onNodeWithTag("orbis-gomoku-board").performScrollTo().assertIsDisplayed()
        val board = compose.onNodeWithTag("orbis-gomoku-board").fetchSemanticsNode().boundsInRoot
        val area = compose.onNodeWithTag("orbis-game-play-area").fetchSemanticsNode().boundsInRoot
        val unclipped = compose.onNodeWithTag("orbis-gomoku-board").getUnclippedBoundsInRoot()
        // All synthetic hosts use density=1. Check the expected measured side as well as the
        // clipped semantics bounds, so a clipped square cannot accidentally satisfy this test.
        val expectedSide = minOf(area.width - 8f, area.height - 4f).coerceAtLeast(1f)
        val layoutWidth = (unclipped.right - unclipped.left).value
        val layoutHeight = (unclipped.bottom - unclipped.top).value
        assertEquals("Actual layout width must use the finite viewport", expectedSide, layoutWidth, 1.5f)
        assertEquals("Actual layout height must use the finite viewport", expectedSide, layoutHeight, 1.5f)
        assertEquals("No horizontal portion may be clipped", layoutWidth, board.width, 1.5f)
        assertEquals("No vertical portion may be clipped", layoutHeight, board.height, 1.5f)
        assertTrue("Board must have positive size: $board", board.width > 0f && board.height > 0f)
        assertTrue("Board must remain square: $board", abs(board.width - board.height) <= 1.5f)
        assertTrue("Board must fit viewport width: $board / $area", board.width <= area.width + 1f)
        assertTrue("Board must fit viewport height: $board / $area", board.height <= area.height + 1f)
        assertTrue("Board top must be reachable: $board / $area", board.top >= area.top - 1f)
        assertTrue("Board bottom must be reachable: $board / $area", board.bottom <= area.bottom + 1f)
        return (area.width - board.width) to (area.height - board.height)
    }

    private fun assertFirstLastCellsAndActionsReachable() {
        listOf("第1行第1列，空位", "第9行第9列，空位").forEach { label ->
            compose.onNodeWithTag("orbis-game-list").performScrollToNode(hasContentDescription(label))
            compose.onNodeWithContentDescription(label).performScrollTo().assertIsDisplayed()
        }
        compose.onNodeWithTag("orbis-game-list").performScrollToNode(hasText("结束本局"))
        compose.onNodeWithText("结束本局").assertIsDisplayed().assertIsEnabled()
    }

    @Test fun narrowPortraitUsesAlmostAllWidthAndKeepsCornersReachable() {
        val fixture = Fixture()
        show(fixture, 280f, 600f)
        val (horizontalGap, _) = assertBoardFitsSquare()
        assertTrue("Portrait board should lose only its small edge gutters", horizontalGap <= 12f)
        assertFirstLastCellsAndActionsReachable()
        compose.runOnIdle { assertEquals(fixture.match, fixture.repository.readSnapshot().active) }
    }

    @Test fun shortLandscapeUsesHeightWithoutStretchingOrLosingTheTop() {
        val fixture = Fixture()
        show(fixture, 600f, 240f)
        val (horizontalGap, verticalGap) = assertBoardFitsSquare()
        assertTrue("Landscape is height-limited, not a stretched rectangle", horizontalGap > 100f)
        assertTrue("Board should make use of the short viewport height", verticalGap <= 8f)
        assertFirstLastCellsAndActionsReachable()
        compose.runOnIdle { assertTrue(fixture.repository.readSnapshot().records.isEmpty()) }
    }

    @Test fun largeFontsStillAllowBoardControlsAndCollapsedExplanationToBeReached() {
        val fixture = Fixture()
        show(fixture, 320f, 400f, fontScale = 2f)
        compose.onNodeWithTag("orbis-game-explanation").assertDoesNotExist()
        assertBoardFitsSquare()
        assertFirstLastCellsAndActionsReachable()
        compose.onNodeWithTag("orbis-game-info-toggle").assertIsDisplayed().performClick()
        compose.onNodeWithTag("orbis-game-list").performScrollToNode(hasTestTag("orbis-game-explanation"))
        compose.onNodeWithTag("orbis-game-explanation").assertIsDisplayed()
        compose.onNodeWithTag("orbis-game-info-toggle").performClick()
        compose.onNodeWithTag("orbis-game-explanation").assertDoesNotExist()
        assertBoardFitsSquare()
        compose.runOnIdle { assertEquals(fixture.match.id, fixture.repository.readSnapshot().active!!.id) }
    }

    @Test fun resizingAnOpenBoardDoesNotStartANewMatchOrForgetItsMoves() {
        val fixture = Fixture()
        fixture.repository.play(fixture.match.id, 40)
        val before = fixture.repository.readSnapshot()
        val viewport = mutableStateOf(280f to 600f)
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1f)) {
                MaterialTheme { OrbisVisualTheme(darkTheme = false) {
                    Box(Modifier.size(viewport.value.first.dp, viewport.value.second.dp)) {
                        GameContent(fixture.repository, fixture.library, null, "合成助手",
                            { error("Layout fixtures must not call a model") }, {})
                    }
                } }
            }
        }
        compose.onNodeWithTag("orbis-game-list").performScrollToNode(hasText("继续本局"))
        compose.onNodeWithText("继续本局").performClick()
        assertBoardFitsSquare()
        compose.runOnIdle { viewport.value = 600f to 240f }
        compose.waitForIdle()
        assertBoardFitsSquare()
        compose.runOnIdle { assertEquals(before, fixture.repository.readSnapshot()) }
    }

    @Test fun shortWindowWithLargeFontKeepsStorageNoticeScrollableAndBoardVisible() {
        val fixture = Fixture()
        fixture.storage.failWrites = true
        assertTrue(runCatching { fixture.repository.play(fixture.match.id, 40) }.isFailure)
        show(fixture, 600f, 240f, fontScale = 2f)
        val notice = compose.onNodeWithTag("orbis-game-storage-notice").fetchSemanticsNode().boundsInRoot
        assertTrue("A storage warning must not consume the short window", notice.height <= 49f)
        assertBoardFitsSquare()
        val reload = compose.onNodeWithText("重新读取并核验记录")
        reload.performScrollTo().assertIsDisplayed().assertIsEnabled()
        fixture.storage.failWrites = false
        reload.performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("orbis-game-storage-notice").assertDoesNotExist()
        assertBoardFitsSquare()
        compose.runOnIdle { assertEquals(fixture.match, fixture.repository.readSnapshot().active) }
    }

    @Test fun htmlShortWindowAndLargeFontKeepGameAndScrollableExitVisibleWithoutRemounting() {
        val fixture = Fixture()
        val source = """<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1"></head><body>合成游戏，不访问网络或文件。</body></html>"""
        val game = fixture.library.install("layout-fixture", "合成 AI", "布局测试游戏", "仅内存测试", source)
        show(fixture, 320f, 240f, fontScale = 2f, openNative = false)
        compose.onNodeWithTag("orbis-game-list").performScrollToNode(hasText(game.title))
        compose.onNodeWithText(game.title).performClick()
        compose.waitForIdle()
        lateinit var mountedView: WebView
        compose.runOnIdle { mountedView = webViews(compose.activity.window.decorView).single() }
        val session = fixture.library.readSnapshot().sessions.single()

        fun assertGameKeepsViewport() {
            val playArea = compose.onNodeWithTag("orbis-html-play-area").fetchSemanticsNode().boundsInRoot
            val gameArea = compose.onNodeWithTag("orbis-html-game").fetchSemanticsNode().boundsInRoot
            val controls = compose.onNodeWithTag("orbis-html-controls").fetchSemanticsNode().boundsInRoot
            assertTrue("The HTML play area must remain positive", playArea.height > 0f)
            assertTrue("At least 55% of the remaining viewport belongs to the game", gameArea.height >= playArea.height * .55f - 1.5f)
            assertTrue("Scrollable chrome must stay bounded", controls.height <= playArea.height * .45f + 1.5f)
            compose.runOnIdle { assertSame("Folding chrome must not recreate the game", mountedView, webViews(compose.activity.window.decorView).single()) }
        }
        assertGameKeepsViewport()
        compose.onNodeWithTag("orbis-game-info-toggle").performClick()
        compose.onNodeWithTag("orbis-game-explanation").performScrollTo().assertIsDisplayed()
        assertGameKeepsViewport()
        compose.onNodeWithText("结束本局").performScrollTo().assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithText("回游戏库").performScrollTo().assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithTag("orbis-game-info-toggle").performClick()
        compose.onNodeWithTag("orbis-game-explanation").assertDoesNotExist()
        assertGameKeepsViewport()

        // A completed result can add another large-font row; it must share the same bounded
        // scroll region, not steal the WebView's height. This writes only the synthetic repository.
        compose.runOnIdle { fixture.library.finish(session.id, "completed", 0) }
        compose.onNodeWithTag("orbis-game-info-toggle").performClick()
        compose.onNodeWithTag("orbis-html-result").performScrollTo().assertIsDisplayed()
        assertGameKeepsViewport()
        compose.onNodeWithText("回游戏库").performScrollTo().performClick()
        compose.onNodeWithTag("orbis-html-game").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(source, fixture.library.readSnapshot().games.single().html)
            assertEquals(game.sha256, fixture.library.readSnapshot().games.single().sha256)
            assertEquals(session.id, fixture.library.readSnapshot().sessions.single().id)
            assertEquals("completed", fixture.library.readSnapshot().sessions.single().result)
        }
    }

    private fun webViews(view: View): List<WebView> = when (view) {
        is WebView -> listOf(view)
        is ViewGroup -> (0 until view.childCount).flatMap { webViews(view.getChildAt(it)) }
        else -> emptyList()
    }
}
