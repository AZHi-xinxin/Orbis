package me.rerere.rikkahub.ui.pages.orbis

import android.app.Application
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import me.rerere.rikkahub.data.ai.IsolatedGenerationLoopRunner
import me.rerere.rikkahub.data.orbis.body.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import java.io.File

class OrbisBodyPanelDeviceTest {
    private val compose = createAndroidComposeRule<ComponentActivity>()
    private val guard = object : ExternalResource() {
        override fun before() {
            val test = InstrumentationRegistry.getInstrumentation()
            check(test is IsolatedGenerationLoopRunner)
            assertEquals(Application::class.java, test.targetContext.applicationContext.javaClass)
        }
    }
    @get:Rule val rules: RuleChain = RuleChain.outerRule(guard).around(compose)
    private fun state(visible: Boolean) = BodyState(revision = 1, running = true,
        versions = listOf(BodyVersion(1, 1, BodyDefinition(listOf(BodyDimension("energy", "活力"))))),
        snapshot = BodySnapshot(sessions = mapOf("chat" to BodySession(mapOf("energy" to 58.0), mapOf("energy" to 60.0), visible))),
        logs = listOf(BodyLog(1, System.currentTimeMillis(), 1, "chat", "rest", values = mapOf("energy" to 58.0))))
    @Test fun visiblePanelShowsSeparateLabelsAndRefreshWithoutNumericEditing() {
        var refreshed = 0
        compose.setContent { MaterialTheme { Surface(Modifier.fillMaxSize().testTag("body-preview")) {
            Column(Modifier.padding(20.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                BodyStateView(state(true), "chat", true, false, listOf("暖"), { refreshed++ })
            }
        } } }
        compose.onAllNodesWithText("活力").onFirst().assertExists()
        compose.onNodeWithText("58.0", substring = true).assertExists()
        compose.onNodeWithText("便签自标", substring = true).assertExists()
        compose.onNodeWithText("刷新").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, refreshed) }
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(0)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.getExternalFilesDir(null)!!, "body-preview")
        check(directory.isDirectory || directory.mkdirs())
        val bitmap = compose.onNodeWithTag("body-preview").captureToImage().asAndroidBitmap()
        File(directory, "body-panel.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    @Test fun hiddenPanelDoesNotRevealCalculatedValuesEvenInSettings() {
        compose.setContent { MaterialTheme { Column {
            BodyStateView(state(false), "chat", true, false, emptyList(), {})
        } } }
        compose.onNodeWithText("当前会话未公开计算状态", substring = true).assertExists()
        compose.onNodeWithText("58.0", substring = true).assertDoesNotExist()
        compose.onNodeWithText("状态走势", substring = true).assertDoesNotExist()
    }
    @Test fun manualReminderAppearsInTheSmallHeartPanelWithoutOpeningSettings() {
        compose.setContent { MaterialTheme { Column {
            BodyStateView(state(false), "chat", false, false, emptyList(), {})
        } } }
        compose.onNodeWithText("尚未开启自动响应").assertExists()
        compose.onNodeWithText("复制给 AI 的说明").assertExists()
        compose.onNodeWithText("手动事件", substring = true).assertExists()
        compose.onNodeWithText("58.0", substring = true).assertDoesNotExist()
    }
}
