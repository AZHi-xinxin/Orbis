package me.rerere.rikkahub.ui.pages.orbis

import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import me.rerere.rikkahub.data.orbis.GomokuRules
import me.rerere.rikkahub.data.orbis.OrbisGameRepository
import me.rerere.rikkahub.data.orbis.OrbisGameStorage
import me.rerere.rikkahub.data.orbis.OrbisMiniGameRepository
import me.rerere.rikkahub.testutil.createShellComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** No Koin, real settings, credentials, disk or network: only injected synthetic repositories. */
@RunWith(AndroidJUnit4::class)
class OrbisGameMachineUiTest {
    @get:Rule val compose = createShellComposeRule()

    @Test fun modelOpponentIsAnExplicitSwitchWithPerGameBudgetChoices() {
        var observed = false
        var count = 40
        compose.setContent { MaterialTheme { OrbisVisualTheme(darkTheme = false) {
            var enabled by remember { mutableStateOf(false) }
            var limit by remember { mutableStateOf(40) }
            Column { ModelOpponentOptions(enabled, "合成助手", true, limit,
                onEnabled = { observed = it; enabled = it }, onLimit = { count = it; limit = it }) }
        } } }
        compose.onNodeWithTag("orbis-game-model-toggle").assertIsOff().performClick()
        compose.onNodeWithTag("orbis-game-model-toggle").assertIsOn()
        compose.onNodeWithText("10 次").performClick()
        compose.runOnIdle { assertTrue(observed); assertEquals(10, count) }
        compose.onNodeWithTag("orbis-game-model-toggle").performClick()
        compose.runOnIdle { assertFalse(observed) }
        compose.onNodeWithText("10 次").assertDoesNotExist()
    }

    @Test fun restoredPendingModelTurnDoesNotMakeAnAutomaticRequest() {
        val native = OrbisGameRepository(MemoryStorage())
        val match = native.start(GomokuRules.MODEL_OPPONENT, "合成助手", "synthetic-assistant", 10)
        native.play(match.id, 40)
        val mini = OrbisMiniGameRepository(MemoryStorage())
        var requests = 0
        compose.setContent { MaterialTheme { OrbisVisualTheme(darkTheme = false) {
            GameContent(native, mini, "synthetic-assistant", "合成助手", { requests++; 0 }, {})
        } } }
        compose.onNodeWithText("继续本局").performScrollTo().performClick()
        compose.onNodeWithText("继续模型落子").performScrollTo().assertExists()
        compose.runOnIdle { assertEquals(0, requests); assertEquals(0, native.readSnapshot().active!!.modelCalls) }
        compose.onNodeWithText("关闭模型，转本地").performScrollTo().performClick()
        compose.waitUntil(5000) { native.state.value.active?.opponent == GomokuRules.MIXED_OPPONENT }
        compose.runOnIdle { assertEquals(0, requests); assertEquals(2, native.readSnapshot().active!!.moves.size) }
    }

    @Test fun explicitEndRecordsExitWithoutAnotherConfirmationDialog() {
        val native = OrbisGameRepository(MemoryStorage())
        val match = native.start()
        compose.setContent { MaterialTheme { OrbisVisualTheme(darkTheme = false) {
            GameContent(native, OrbisMiniGameRepository(MemoryStorage()), null, "未选择 AI", { error("No model request") }, {})
        } } }
        compose.onNodeWithText("继续本局").performScrollTo().performClick()
        compose.onNodeWithText("结束本局").performScrollTo().performClick()
        compose.waitUntil(5000) { native.state.value.records.isNotEmpty() }
        compose.onNodeWithText("确认退出").assertDoesNotExist()
        compose.runOnIdle {
            assertNull(native.readSnapshot().active)
            assertEquals(match.id, native.readSnapshot().records.single().match.id)
            assertEquals("abandoned", native.readSnapshot().records.single().result)
        }
    }

    @Test fun htmlHistoryStartsFoldedAndPagesFiveWithoutDroppingOldRecords() {
        val mini = OrbisMiniGameRepository(MemoryStorage())
        val game = mini.install("synthetic", "合成助手", "合成游戏", "离线测试", "<html>synthetic</html>")
        val ids = (0 until 12).map { mini.start(game.id).also { mini.finish(it.id, "draw", 2) }.id }
        compose.setContent { MaterialTheme { OrbisVisualTheme(darkTheme = false) {
            GameContent(OrbisGameRepository(MemoryStorage()), mini, null, "未选择 AI", { error("No model") }, {})
        } } }
        compose.onNodeWithTag("orbis-game-list").performScrollToNode(hasTestTag("html-records-toggle"))
        compose.onNodeWithTag("html-record-${ids.last()}").assertDoesNotExist()
        compose.onNodeWithTag("html-records-toggle").performClick()
        ids.takeLast(5).forEach { compose.onNodeWithTag("html-record-$it").assertExists() }
        compose.onNodeWithTag("html-record-${ids[6]}").assertDoesNotExist()
        compose.onNodeWithTag("html-records-next").performScrollTo().performClick()
        compose.onNodeWithTag("html-record-${ids.last()}").assertDoesNotExist()
        ids.subList(2, 7).forEach { compose.onNodeWithTag("html-record-$it").assertExists() }
        compose.onNodeWithTag("html-records-next").performScrollTo().performClick()
        ids.take(2).forEach { compose.onNodeWithTag("html-record-$it").assertExists() }
        compose.onNodeWithTag("html-records-next").assertIsNotEnabled()
        assertEquals(12, mini.readSnapshot().sessions.size)
    }

    @Test fun legacyGameWithoutCheckpointRequiresExplicitDecisionAndPreservesItsOldSession() {
        val mini = OrbisMiniGameRepository(MemoryStorage())
        val game = mini.install("synthetic", "合成助手", "旧模板", "离线测试", "<html>synthetic</html>")
        val old = mini.start(game.id)
        val shown = androidx.compose.runtime.mutableStateOf(true)
        compose.setContent { MaterialTheme { OrbisVisualTheme(darkTheme = false) {
            if(shown.value) androidx.compose.ui.window.Dialog(onDismissRequest = { shown.value = false }, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
                GameContent(OrbisGameRepository(MemoryStorage()), mini, null, "未选择 AI", { error("No model") }, { shown.value = false })
            }
        } } }
        compose.onNodeWithTag("html-resume-${old.id}").assertIsDisplayed().performClick()
        compose.onNodeWithText("保留记录，回游戏库").assertIsDisplayed().performTouchInput { click(center) }
        compose.onNodeWithText("旧局尚不能恢复").assertDoesNotExist()
        compose.onNodeWithText("保留记录，回游戏库").assertDoesNotExist()
        compose.onNodeWithTag("html-resume-${old.id}").assertIsDisplayed().performClick()
        compose.onNodeWithText("旧局尚不能恢复").assertIsDisplayed()
        compose.onNodeWithText("复制给 AI 的修复说明").performClick()
        compose.onNodeWithText("已复制，回聊天粘贴给 AI").assertExists()
        compose.onNodeWithText("保留记录，回游戏库").performClick()
        compose.runOnIdle { assertTrue(shown.value) }
        compose.runOnIdle { assertEquals(listOf(old), mini.readSnapshot().sessions) }
        compose.onNodeWithTag("orbis-html-game").assertDoesNotExist()
    }

    @Test fun savedMatchCanResumeFromTopAndHistoryAfterLeavingAndReopeningTheGameMachine() {
        val storage = MemoryStorage()
        val mini = OrbisMiniGameRepository(storage)
        val game = mini.install("synthetic", "AI", "可续局合成游戏", "", """
            <!doctype html><html><body><script>
            const r = JSON.parse(OrbisGame.loadState());
            if (r.ok && r.state) {
              OrbisGame.saveState(JSON.stringify({revision:r.revision,
                state:{turn:r.state.turn,visits:(r.state.visits||0)+1}}));
              document.body.appendChild(document.createTextNode('turn '+r.state.turn));
            }
            </script></body></html>
        """.trimIndent())
        val first = mini.start(game.id); mini.saveCheckpoint(first.id, 0, "{\"turn\":3}")
        val newer = mini.start(game.id); mini.saveCheckpoint(newer.id, 0, "{\"turn\":7}")
        val current = mutableStateOf(mini)
        val native = OrbisGameRepository(MemoryStorage())
        compose.setContent { MaterialTheme { OrbisVisualTheme(darkTheme = false) {
            androidx.compose.runtime.key(current.value) {
                GameContent(native, current.value, null, "AI", { error("No model") }, {})
            }
        } } }
        compose.onNodeWithTag("html-resume-${first.id}").performScrollTo().performClick()
        compose.waitUntil(15000) { mini.state.value.sessions.first().checkpointRevision == 2L }
        compose.onNodeWithText("回游戏库").performClick()
        compose.onNodeWithTag("orbis-game-list").performScrollToNode(hasTestTag("html-records-toggle"))
        compose.onNodeWithTag("html-records-toggle").performClick()
        compose.onNodeWithTag("html-history-resume-${first.id}").performScrollTo().performClick()
        compose.waitUntil(15000) { mini.state.value.sessions.first().checkpointRevision == 3L }
        compose.runOnIdle { current.value = OrbisMiniGameRepository(storage) }
        compose.onNodeWithTag("html-resume-${first.id}").performScrollTo().performClick()
        compose.waitUntil(15000) { current.value.state.value.sessions.first().checkpointRevision == 4L }
        compose.runOnIdle {
            val sessions = current.value.readSnapshot().sessions
            assertEquals(listOf(first.id, newer.id), sessions.map { it.id })
            assertEquals("{\"turn\":3,\"visits\":3}", sessions.first().checkpoint)
            assertEquals("{\"turn\":7}", sessions.last().checkpoint)
            assertTrue(sessions.all { it.finishedAt == null })
        }
    }

    private class MemoryStorage : OrbisGameStorage {
        private var text: String? = null
        override fun read() = text
        override fun write(value: String) { text = value }
    }
}
