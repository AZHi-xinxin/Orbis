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

    private class MemoryStorage : OrbisGameStorage {
        private var text: String? = null
        override fun read() = text
        override fun write(value: String) { text = value }
    }
}
