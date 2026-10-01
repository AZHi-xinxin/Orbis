package me.rerere.rikkahub.ui.pages.orbis

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.graphics.Bitmap
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.IsolatedGenerationLoopRunner
import me.rerere.rikkahub.data.datastore.DisplaySetting
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.OrbisAppearance
import me.rerere.rikkahub.data.model.OrbisChatFlowSettings
import me.rerere.rikkahub.data.model.orbisVoiceNoteMetadata
import me.rerere.rikkahub.testutil.createShellComposeRule
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.ui.context.Navigator
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Actual drawer/shared voice renderer. No Koin, database, mic, provider, media file or device settings. */
@RunWith(AndroidJUnit4::class)
class OrbisVoiceNotePresentationUiTest {
    @get:Rule val compose = createShellComposeRule()
    private val audio = UIMessagePart.Audio("file:///synthetic/voice.wav",
        orbisVoiceNoteMetadata("合成语音正文", durationMs = 2200, noteId = "stage93-synthetic-note"))
    private val tool = UIMessagePart.Tool("synthetic-call", "orbis_voice_note", "{\"text\":\"合成语音正文\"}", listOf(audio))
    private val original = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(
        UIMessagePart.Reasoning("synthetic reasoning retained for details"), tool, UIMessagePart.Text("  ")))
    private val current = mutableStateOf(original)
    private val visible = mutableStateOf(true)
    private var playedCallbacks = 0
    private val preferences = ReadOnlyPreferences()

    private fun show() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        check(instrumentation is IsolatedGenerationLoopRunner)
        val context = object : ContextWrapper(instrumentation.targetContext) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                check(name == "orbis_voice_note_played" && mode == Context.MODE_PRIVATE)
                return preferences
            }
            override fun getFilesDir(): File = error("Synthetic fixture cannot open media storage")
        }
        val settings = Settings(displaySetting = DisplaySetting(showUserAvatar = false,
            showModelIcon = false, showDateTimeInMessage = false,
            orbisAppearance = OrbisAppearance(floatingStars = false, chatFlow = OrbisChatFlowSettings(enabled = false))))
        compose.setContent {
            CompositionLocalProvider(LocalContext provides context, LocalSettings provides settings,
                LocalNavController provides Navigator(mutableListOf()), LocalOrbisDeepSeekStyle provides false) {
                MaterialTheme { OrbisVisualTheme(darkTheme = false) {
                    Column(Modifier.requiredWidth(320.dp)) {
                        if (visible.value) GardenQuickChatMessage(current.value, null, null, false,
                            onVoiceNotePlayed = { _, _, _ -> playedCallbacks++ })
                    }
                } }
            }
        }
    }

    @Test fun successfulVoiceIsIndependentWithNoBlankProseOrDebugCardAndDetailsAreOptIn() {
        show()
        compose.onAllNodesWithTag("orbis-voice-independent-row").assertCountEquals(1)
        compose.onNodeWithTag("orbis-voice-prose-row").assertDoesNotExist()
        captureIndependentVoiceScreenshot()
        assertUnreadIndicatorVisible()
        compose.onNodeWithText("3″").assertIsDisplayed()
        compose.onNodeWithText("orbis_voice_note", substring = true).assertDoesNotExist()
        compose.onNodeWithText("synthetic reasoning", substring = true).assertDoesNotExist()
        compose.onNodeWithText("合成语音正文", substring = true).assertDoesNotExist()
        compose.onNodeWithText("▂ ▄ ▆ ▃ ▅").performTouchInput { longClick() }
        compose.onNodeWithText("生成详情").performClick()
        compose.onNodeWithTag("orbis-voice-note-details").assertIsDisplayed()
        compose.onNodeWithText("orbis_voice_note", substring = true).assertExists()
        compose.onNodeWithText("synthetic reasoning", substring = true).assertExists()
        compose.onNodeWithText("关闭").performClick()
        compose.onNodeWithTag("orbis-voice-note-details").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, playedCallbacks); assertEquals(0, preferences.writes); assertEquals(original, current.value) }
    }

    @Test fun longPressTranscriptDoesNotPlayOrMarkHeardAndReopeningDoesNotInventTextBubble() {
        show()
        compose.onNodeWithText("▂ ▄ ▆ ▃ ▅").performTouchInput { longClick() }
        compose.onNodeWithText("转文字").performClick()
        compose.onNodeWithText("合成语音正文").assertIsDisplayed()
        assertUnreadIndicatorVisible()
        compose.runOnIdle { visible.value = false }
        compose.runOnIdle { visible.value = true }
        compose.onAllNodesWithTag("orbis-voice-independent-row").assertCountEquals(1)
        compose.onNodeWithText("合成语音正文").assertDoesNotExist()
        compose.onNodeWithTag("orbis-voice-prose-row").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, playedCallbacks); assertEquals(0, preferences.writes) }
    }

    @Test fun restoredPlayedMetadataStaysHeardAfterFileRelocationAndReentry() {
        val restoredAudio = audio.copy(url = "file:///different-device/voice.wav",
            metadata = JsonObject(audio.metadata!! + ("voice_note_played" to JsonPrimitive(true))))
        current.value = original.copy(parts = listOf(tool.copy(output = listOf(restoredAudio))))
        show()
        compose.onNodeWithTag("orbis-voice-note-unheard", useUnmergedTree = true).assertDoesNotExist()
        compose.runOnIdle { visible.value = false }
        compose.runOnIdle { visible.value = true }
        compose.onNodeWithTag("orbis-voice-note-unheard", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithText("播放中").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, playedCallbacks); assertEquals(0, preferences.writes) }
    }

    @Test fun failedManualPlaybackKeepsUnreadAndDoesNotEmitPersistedHeardEdit() {
        show()
        compose.onNodeWithText("▂ ▄ ▆ ▃ ▅").performClick()
        compose.onNodeWithText("暂时无法播放，请检查本机语音文件是否仍在。").assertIsDisplayed()
        assertUnreadIndicatorVisible()
        compose.runOnIdle { assertEquals(0, playedCallbacks); assertEquals(0, preferences.writes) }
    }

    private fun assertUnreadIndicatorVisible() {
        // combinedClickable merges the Row's semantics, not the child's TestTag.
        // Inspect the real Text node and its pixels: mere existence of a tag is not
        // enough to prove the unread dot is visible (or even has non-zero bounds).
        val dot = compose.onNodeWithTag("orbis-voice-note-unheard", useUnmergedTree = true)
        dot.assertIsDisplayed()
        val pixels = dot.captureToImage().toPixelMap()
        assertTrue("Unread dot must occupy visible pixels", pixels.width > 0 && pixels.height > 0)
        var redPixels = 0
        for (y in 0 until pixels.height) for (x in 0 until pixels.width) {
            val color = pixels[x, y]
            if (color.red > .65f && color.green < .50f && color.blue < .55f) redPixels++
        }
        assertTrue("Unread dot must actually render red, not only expose semantics", redPixels >= 3)
    }

    private fun captureIndependentVoiceScreenshot() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        check(instrumentation is IsolatedGenerationLoopRunner)
        // This captures the synthetic independent row, never a device screenshot or real chat.
        val bitmap = compose.onNodeWithTag("orbis-voice-independent-row")
            .captureToImage().asAndroidBitmap()
        val root = instrumentation.targetContext.cacheDir.canonicalFile
        val target = File.createTempFile("stage93-synthetic-independent-voice-note-", ".png", root)
        check(target.canonicalFile == target.absoluteFile && target.parentFile == root)
        target.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        println(target.name)
    }

    private class ReadOnlyPreferences : SharedPreferences {
        var writes = 0
        override fun getAll(): MutableMap<String, *> = mutableMapOf<String, Any>()
        override fun getString(key: String?, defValue: String?): String? = defValue
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? = defValues?.toMutableSet()
        override fun getInt(key: String?, defValue: Int): Int = defValue
        override fun getLong(key: String?, defValue: Long): Long = defValue
        override fun getFloat(key: String?, defValue: Float): Float = defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = defValue
        override fun contains(key: String?): Boolean = false
        override fun edit(): SharedPreferences.Editor { writes++; error("No fixture preference writes permitted") }
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
    }
}
