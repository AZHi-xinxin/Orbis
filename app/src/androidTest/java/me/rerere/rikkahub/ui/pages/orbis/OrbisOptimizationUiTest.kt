package me.rerere.rikkahub.ui.pages.orbis

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.graphics.Bitmap
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeRight
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import me.rerere.ai.ui.UIMessagePart
import me.rerere.asr.ASRCorrectionResult
import me.rerere.asr.ASRCorrectionNotice
import me.rerere.rikkahub.data.ai.IsolatedGenerationLoopRunner
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.OrbisGenerationParameters
import me.rerere.rikkahub.data.model.orbisVoiceNoteMetadata
import me.rerere.rikkahub.testutil.createShellComposeRule
import me.rerere.rikkahub.ui.components.ai.AsrCorrectionReview
import me.rerere.rikkahub.ui.components.message.OrbisVoiceNoteBubble
import me.rerere.rikkahub.ui.pages.chat.OrbisGenerationParametersSection
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Stateless/synthetic UI only. No Koin, model, mic, TTS, real file, network or user settings. */
@RunWith(AndroidJUnit4::class)
class OrbisOptimizationUiTest {
    @get:Rule val compose = createShellComposeRule()

    @Test fun contextSliderSavesEightyThenExplicitlyRestoresUnlimitedWithoutAutoSavingDraft() {
        val assistant = mutableStateOf(Assistant(name = "Synthetic UI", contextMessageLimit = 0))
        val savedLimits = mutableListOf<Int>()
        var savedCallbacks = 0
        val saving = mutableListOf<Boolean>()
        compose.setContent {
            MaterialTheme { OrbisVisualTheme(darkTheme = false) {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    OrbisGenerationParametersSection("synthetic-conversation", assistant.value, true,
                        onSavingChange = { saving.add(it) }, onSaved = { savedCallbacks++ }, onSave = { edit ->
                            assistant.value = edit.mergeInto(assistant.value)
                            savedLimits.add(assistant.value.contextMessageLimit)
                            OrbisGenerationParameters.from(assistant.value)
                        })
                }
            } }
        }
        compose.onNodeWithText("不限制消息数量").assertExists()
        setContextLimit(80f)
        compose.onNodeWithTag("orbis-generation-context").assertTextContains("80")
        compose.runOnIdle { assertTrue(savedLimits.isEmpty()); assertEquals(0, assistant.value.contextMessageLimit) }
        captureSyntheticScreenshot("stage92-synthetic-context-slider-")
        compose.onNodeWithTag("orbis-generation-save").performScrollTo().performClick()
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(listOf(80), savedLimits); assertEquals(80, assistant.value.contextMessageLimit) }
        compose.onNodeWithTag("orbis-generation-save").assertIsNotEnabled()
        setContextLimit(0f)
        compose.onNodeWithText("不限制消息数量").assertExists()
        compose.runOnIdle { assertEquals(80, assistant.value.contextMessageLimit) }
        compose.onNodeWithTag("orbis-generation-save").performScrollTo().performClick()
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(listOf(80, 0), savedLimits)
            assertEquals(0, assistant.value.contextMessageLimit)
            assertEquals(2, savedCallbacks)
            assertEquals(listOf(true, false, true, false), saving)
        }
        compose.onNodeWithTag("orbis-generation-save").assertIsNotEnabled()
    }

    @Test fun nonEditableContextSliderCannotChangeDraftOrSave() {
        var saves = 0
        compose.setContent {
            MaterialTheme { OrbisVisualTheme(darkTheme = false) {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    OrbisGenerationParametersSection("synthetic-readonly", Assistant(contextMessageLimit = 0), false,
                        onSavingChange = {}, onSaved = {}, onSave = { saves++; it.after })
                }
            } }
        }
        compose.onNodeWithTag("orbis-generation-context-slider").performScrollTo().assertIsNotEnabled()
            .performTouchInput { swipeRight() }
        compose.onNodeWithTag("orbis-generation-context").assertTextContains("0").assertIsNotEnabled()
        compose.onNodeWithText("不限制消息数量").assertExists()
        compose.onNodeWithTag("orbis-generation-save").performScrollTo().assertIsNotEnabled()
        compose.runOnIdle { assertEquals(0, saves) }
    }

    @Test fun asrCorrectionReviewShowsBothOriginalAndCorrectedWithoutApplyingEitherOnOpen() {
        val review = ASRCorrectionResult("合成原识别甲", "合成纠正后乙")
        var originalUses = 0
        compose.setContent { MaterialTheme { AsrCorrectionReview(review, onUseOriginal = { originalUses++ }) } }
        compose.onNodeWithText("合成原识别甲", substring = true).assertDoesNotExist()
        compose.onNodeWithText("已纠正称呼 · 核对原识别").performClick()
        compose.onNodeWithText("语音识别核对").assertIsDisplayed()
        compose.onNodeWithText("原识别：\n合成原识别甲\n\n纠正后：\n合成纠正后乙").assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, originalUses) }
        compose.onNodeWithText("知道了").performClick()
        compose.onNodeWithText("语音识别核对").assertDoesNotExist()
        compose.onNodeWithText("已纠正称呼 · 核对原识别").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, originalUses) }
    }

    @Test fun correctionNoticeDismissesOncePerCaptureIncludingIdenticalNextUtterance() {
        val result = ASRCorrectionResult("合成原识别甲", "合成纠正后乙")
        val notice = mutableStateOf(ASRCorrectionNotice().begin(1).accept(1, result))
        val mounted = mutableStateOf(true)
        var dismissals = 0
        var originalUses = 0
        compose.setContent { MaterialTheme { if (mounted.value) AsrCorrectionReview(
            review = notice.value.review, eventId = notice.value.eventId, dismissed = notice.value.dismissed,
            onDismiss = { notice.value = notice.value.dismiss(notice.value.eventId); dismissals++ },
            onUseOriginal = { originalUses++ },
        ) } }
        compose.onNodeWithText("已纠正称呼 · 核对原识别").performClick()
        compose.onNodeWithText("知道了").performClick()
        compose.onNodeWithText("已纠正称呼 · 核对原识别").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(1, dismissals)
            assertEquals(0, originalUses)
            assertEquals(result, notice.value.review)
            // A later partial/final and re-entering the UI cannot resurrect this prompt.
            notice.value = notice.value.accept(1, result.copy(corrected = "合成纠正后乙。"))
            mounted.value = false
        }
        compose.waitForIdle()
        compose.runOnIdle { mounted.value = true }
        compose.onNodeWithText("已纠正称呼 · 核对原识别").assertDoesNotExist()
        compose.runOnIdle { notice.value = notice.value.begin(2).accept(2, result) }
        compose.onNodeWithText("已纠正称呼 · 核对原识别").assertIsDisplayed()
        compose.onNodeWithTag("asr-correction-dismiss").performClick()
        compose.onNodeWithText("已纠正称呼 · 核对原识别").assertDoesNotExist()
        compose.onNodeWithText("语音识别核对").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(2, dismissals)
            assertEquals(0, originalUses)
            assertEquals(result, notice.value.review)
        }
    }

    @Test fun unchangedAsrResultDoesNotClaimCorrectionOrExposeReviewButton() {
        compose.setContent { MaterialTheme { Column {
            Text("Synthetic unchanged fixture")
            AsrCorrectionReview(ASRCorrectionResult("合成未改文字", "合成未改文字"))
        } } }
        compose.onNodeWithText("Synthetic unchanged fixture").assertIsDisplayed()
        compose.onNodeWithText("已纠正称呼 · 核对原识别").assertDoesNotExist()
        compose.onNodeWithText("语音识别核对").assertDoesNotExist()
    }

    @Test fun voiceNoteLongPressRevealsTranscriptWithoutPlaybackOrWritingHeardState() {
        val fixture = IsolatedVoiceContext()
        val audio = UIMessagePart.Audio("file:///synthetic-no-file/voice-note.wav", orbisVoiceNoteMetadata("合成语音条转写", durationMs = 1400))
        compose.setContent { CompositionLocalProvider(LocalContext provides fixture.context) {
            MaterialTheme { OrbisVoiceNoteBubble(audio, "synthetic-voice-transcript") }
        } }
        compose.onNodeWithText("合成语音条转写").assertDoesNotExist()
        compose.onNodeWithText("2″").assertIsDisplayed()
        compose.onNodeWithText("▂ ▄ ▆ ▃ ▅").performTouchInput { longClick() }
        compose.onNodeWithText("转文字").performClick()
        compose.onNodeWithText("合成语音条转写").assertIsDisplayed()
        compose.onNodeWithText("播放中").assertDoesNotExist()
        compose.onNodeWithText("暂时无法播放", substring = true).assertDoesNotExist()
        compose.onNodeWithText("●").assertIsDisplayed()
        compose.runOnIdle { fixture.assertReadOnly() }
        captureSyntheticScreenshot("stage92-synthetic-voice-note-")
    }

    @Test fun voiceNoteWithoutTranscriptShowsHonestPlaceholderAndCanCollapseReadOnly() {
        val fixture = IsolatedVoiceContext()
        val audio = UIMessagePart.Audio("file:///synthetic-no-file/empty.wav", orbisVoiceNoteMetadata(""))
        compose.setContent { CompositionLocalProvider(LocalContext provides fixture.context) {
            MaterialTheme { OrbisVoiceNoteBubble(audio, "synthetic-voice-empty") }
        } }
        compose.onNodeWithText("▂ ▄ ▆ ▃ ▅").performTouchInput { longClick() }
        compose.onNodeWithText("转文字").performClick()
        compose.onNodeWithText("这条语音没有保存转写文字。").assertIsDisplayed()
        compose.onNodeWithText("▂ ▄ ▆ ▃ ▅").performTouchInput { longClick() }
        compose.onNodeWithText("收起文字").performClick()
        compose.onNodeWithText("这条语音没有保存转写文字。").assertDoesNotExist()
        compose.onNodeWithText("播放中").assertDoesNotExist()
        compose.onNodeWithText("暂时无法播放", substring = true).assertDoesNotExist()
        compose.runOnIdle { fixture.assertReadOnly() }
    }

    private fun setContextLimit(value: Float) {
        compose.onNodeWithTag("orbis-generation-context-slider").performScrollTo()
            .performSemanticsAction(SemanticsActions.SetProgress) { action -> assertTrue(action(value)) }
    }

    private fun captureSyntheticScreenshot(prefix: String) {
        check(prefix in setOf("stage92-synthetic-context-slider-", "stage92-synthetic-voice-note-"))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        check(instrumentation is IsolatedGenerationLoopRunner)
        // This root is only this rule's nonce-owned synthetic ComponentActivity, not the device screen.
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        // Instrumentation runs with the target UID, not the test package UID. Create a new
        // temporary file only; never overwrite or enumerate the target's existing cache.
        val root = instrumentation.targetContext.cacheDir.canonicalFile
        val target = File.createTempFile(prefix, ".png", root)
        check(target.canonicalFile == target.absoluteFile && target.parentFile == root)
        target.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        println(target.name)
    }

    private class IsolatedVoiceContext {
        private val instrumentation = InstrumentationRegistry.getInstrumentation().also {
            check(it is IsolatedGenerationLoopRunner) { "Only the isolated runner may render these fixtures" }
        }
        private val preferences = ReadOnlyMemoryPreferences()
        private val opened = mutableSetOf<String>()
        val context: Context = object : ContextWrapper(instrumentation.targetContext) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                check(name == "orbis_voice_note_played") { "Unexpected preference access in synthetic fixture" }
                check(mode == Context.MODE_PRIVATE)
                opened.add(name)
                return preferences
            }
        }
        fun assertReadOnly() {
            assertEquals(setOf("orbis_voice_note_played"), opened)
            assertTrue(preferences.all.isEmpty())
            assertTrue(preferences.booleanReads > 0)
            assertEquals(0, preferences.editAttempts)
            assertSame(context, context.applicationContext)
        }
    }

    /** Deliberately empty and immutable: no read or write reaches disk preferences. */
    private class ReadOnlyMemoryPreferences : SharedPreferences {
        var booleanReads = 0
            private set
        var editAttempts = 0
            private set
        override fun getAll(): MutableMap<String, *> = mutableMapOf<String, Any>()
        override fun getString(key: String?, defValue: String?): String? = defValue
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
            defValues?.toMutableSet()
        override fun getInt(key: String?, defValue: Int): Int = defValue
        override fun getLong(key: String?, defValue: Long): Long = defValue
        override fun getFloat(key: String?, defValue: Float): Float = defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean {
            booleanReads++
            return defValue
        }
        override fun contains(key: String?): Boolean = false
        override fun edit(): SharedPreferences.Editor {
            editAttempts++
            error("Synthetic read-only voice fixture must never edit preferences")
        }
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
    }
}
