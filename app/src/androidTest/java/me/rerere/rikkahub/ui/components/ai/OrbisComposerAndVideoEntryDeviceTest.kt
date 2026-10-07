package me.rerere.rikkahub.ui.components.ai

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextField
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import me.rerere.rikkahub.data.ai.IsolatedGenerationLoopRunner
import me.rerere.rikkahub.ui.pages.orbis.OrbisVisualTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import kotlin.math.abs

/** Synthetic UI only: no production application, camera, microphone, account or chat store. */
@RunWith(AndroidJUnit4::class)
class OrbisComposerAndVideoEntryDeviceTest {
    private val compose = createAndroidComposeRule<ComponentActivity>()
    private val isolated = object : ExternalResource() {
        override fun before() {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            check(instrumentation is IsolatedGenerationLoopRunner)
            assertEquals(Application::class.java, instrumentation.targetContext.applicationContext.javaClass)
        }
    }
    @get:Rule val rules: RuleChain = RuleChain.outerRule(isolated).around(compose)

    @Test fun transparentComposerAndTextFieldHaveOneUniformBackgroundAtEveryOpacity() {
        val opacity = mutableStateOf(1f)
        val wallpaper = mutableStateOf(Color(0xFFF5DDE8))
        compose.setContent { MaterialTheme {
            Box(Modifier.fillMaxSize().background(wallpaper.value).padding(24.dp)) {
                ChatComposerSurface(Modifier.width(300.dp).height(80.dp)
                    .clip(RoundedCornerShape(23.dp)).testTag("composer-background"),
                    shape = RoundedCornerShape(23.dp), color = Color.White.copy(alpha = opacity.value),
                    contentColor = Color.Black, border = BorderStroke(1.dp, Color.Gray), orbis = true) {
                    TextField(state = rememberTextFieldState(), modifier = Modifier.fillMaxWidth().padding(8.dp),
                        colors = chatInputFieldColors())
                }
            }
        } }
        for (background in listOf(Color(0xFFF5DDE8), Color(0xFF36789C), Color(0xFF182237))) {
            for (alpha in listOf(1f, .75f, .5f, .15f)) {
                compose.runOnIdle { opacity.value = alpha; wallpaper.value = background }
                val pixels = compose.onNodeWithTag("composer-background").captureToImage().toPixelMap()
                val expected = Color.White.copy(alpha = alpha).compositeOver(background)
                // Include both the TextField's interior and the strip below its text baseline.
                for (x in listOf(.25f, .5f, .75f)) for (y in listOf(.2f, .45f, .7f, .85f)) {
                    val actual = pixels[(pixels.width * x).toInt(), (pixels.height * y).toInt()]
                    assertTrue("alpha=$alpha x=$x y=$y expected=$expected actual=$actual",
                        abs(actual.red - expected.red) < .025f && abs(actual.green - expected.green) < .025f &&
                            abs(actual.blue - expected.blue) < .025f)
                }
            }
        }
    }

    @Test fun videoButtonIsVisibleInCurrentVoicePanelAndOnlyHumanTapStartsIt() {
        var starts = 0
        val allowed = mutableStateOf(true)
        val active = mutableStateOf(false)
        compose.setContent { MaterialTheme { OrbisVisualTheme(darkTheme = false) {
            Box(Modifier.width(360.dp)) {
                OrbisVoicePanel(canRecognize = true, recording = false, busy = false,
                    canStartVoice = true, canSpeak = true, speaking = false, autoRead = false,
                    onRecognize = {}, onStartVoice = {}, onStopVoice = {}, voiceActive = active.value,
                    onSpeak = {}, onStopSpeaking = {}, onConfigure = {},
                    canStartVideo = allowed.value, videoUnavailableReason = "请先选择支持图片输入的模型。",
                    onStartVideo = { starts++ })
            }
        } } }
        compose.onNodeWithTag("orbis-start-video-call").assertIsDisplayed().assertIsEnabled()
        compose.runOnIdle { assertEquals(0, starts) }
        compose.onNodeWithTag("orbis-start-video-call").performClick()
        compose.runOnIdle { assertEquals(1, starts); allowed.value = false }
        compose.onNodeWithTag("orbis-start-video-call").assertIsDisplayed().assertIsNotEnabled()
        compose.onNodeWithText("暂不能视频：请先选择支持图片输入的模型。").assertExists()
        compose.runOnIdle { allowed.value = true; active.value = true }
        compose.onNodeWithTag("orbis-start-video-call").assertIsNotEnabled()
        compose.runOnIdle { assertEquals(1, starts) }
    }
}
