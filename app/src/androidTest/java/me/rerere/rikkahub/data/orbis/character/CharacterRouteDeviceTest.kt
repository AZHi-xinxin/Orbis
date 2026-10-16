package me.rerere.rikkahub.data.orbis.character

import android.app.Application
import android.content.Intent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.cancel
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.ai.IsolatedGenerationLoopRunner
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.ui.activity.OrbisCharacterActivity
import me.rerere.tts.provider.TTSManager
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import kotlin.uuid.Uuid

/** In-memory assistants and empty random-owner stages only. Never load real settings or call a model. */
@RunWith(AndroidJUnit4::class)
class CharacterRouteDeviceTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun warmEntryChangesOwnerAndUnknownOwnerNeverKeepsPreviousStage() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        check(instrumentation is IsolatedGenerationLoopRunner)
        val context = instrumentation.targetContext
        assertEquals(Application::class.java, context.applicationContext.javaClass)
        check(GlobalContext.getOrNull() == null) { "Do not replace another fixture's Koin context" }
        val dormant = AppScope().also { it.cancel() }
        val a = Assistant(name = "Synthetic route A")
        val b = Assistant(name = "Synthetic route B")
        val settings = SettingsStore(context, dormant).also {
            it.settingsFlow.value = Settings(assistants = listOf(a, b), assistantId = a.id,
                providers = emptyList(), mcpServers = emptyList(), ttsProviders = emptyList())
        }
        startKoin { modules(module {
            single { settings }
            single { TTSManager(context) }
        }) }
        fun intent(id: String) = Intent(context, OrbisCharacterActivity::class.java)
            .putExtra("character_owner", id)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        fun waitFor(text: String) {
            compose.waitUntil(10_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText(text).assertIsDisplayed()
        }
        try {
            ActivityScenario.launch<OrbisCharacterActivity>(intent(a.id.toString())).use { scenario ->
                waitFor(a.name + " · 让 TA 陪在桌面上")
                context.startActivity(intent(b.id.toString()))
                waitFor(b.name + " · 让 TA 陪在桌面上")
                compose.onNodeWithText(a.name + " · 让 TA 陪在桌面上").assertDoesNotExist()
                scenario.recreate()
                waitFor(b.name + " · 让 TA 陪在桌面上")
                context.startActivity(intent(Uuid.random().toString()))
                waitFor("这个入口尚未绑定，或原助手已不存在。不会自动打开其他助手。")
                compose.onNodeWithText(a.name + " · 让 TA 陪在桌面上").assertDoesNotExist()
                compose.onNodeWithText(b.name + " · 让 TA 陪在桌面上").assertDoesNotExist()
            }
        } finally { stopKoin() }
    }
}
