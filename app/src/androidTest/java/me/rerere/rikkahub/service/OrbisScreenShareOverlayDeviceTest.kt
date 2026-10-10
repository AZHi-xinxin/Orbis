package me.rerere.rikkahub.service

import android.app.Application
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.ContextThemeWrapper
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import me.rerere.rikkahub.data.ai.IsolatedGenerationLoopRunner
import me.rerere.rikkahub.data.orbis.screenshare.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Native compact views only. No window permission, capture, singleton runtime, microphone or model. */
@RunWith(AndroidJUnit4::class)
class OrbisScreenShareOverlayDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    @Before fun isolated() {
        check(instrumentation is IsolatedGenerationLoopRunner)
        assertEquals(Application::class.java, instrumentation.targetContext.applicationContext.javaClass)
    }

    private fun onMain(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private fun create(fontScale: Float = 1f, called: MutableList<String> = mutableListOf()): OrbisScreenShareOverlay {
        val base = instrumentation.targetContext
        val context = ContextThemeWrapper(base.createConfigurationContext(Configuration(base.resources.configuration).apply {
            this.fontScale = fontScale
        }), android.R.style.Theme_Material_NoActionBar)
        return OrbisScreenShareOverlay(context, ScreenShareOverlayActions(
            onCollapse = { called += "collapse" }, onExpand = { called += "expand" },
            onRetry = { called += "retry" }, onReconnect = { called += "reconnect" },
            onScreen = { called += "screen" }, onMicrophone = { called += "microphone" },
            onStop = { called += "stop" }, onInterval = { called += "interval" },
            onSpeechOutput = { called += "speech" }, onInputTouched = { called += "input" },
            onSend = { called += "send:$it" },
        ))
    }
    private fun OrbisScreenShareOverlay.find(tag: String): View = requireNotNull(view.findViewWithTag(tag))
    private fun OrbisScreenShareOverlay.layout(collapsed: Boolean = false) {
        val density = view.resources.displayMetrics.density
        val size = screenShareOverlaySize(density, ScreenShareOverlayArea(0, 0, (400 * density).toInt(), (800 * density).toInt()), collapsed)
        setGeometry(size, collapsed)
        view.measure(View.MeasureSpec.makeMeasureSpec(size.width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(size.height, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, size.width, size.height)
    }
    private fun ready(reply: String = "真实回复") = ScreenShareOverlayUiState(
        health = ScreenShareHealth.READY, healthText = "画面就绪", sessionActive = true, latestReply = reply,
    )

    @Test fun hugeSystemFontAndLongResponseStillFitSmallFixedWindow() = onMain {
        val ui = create(fontScale = 3f)
        val reply = "只有这段是 AI 回复。".repeat(300)
        ui.render(ready(reply)); ui.layout()
        val density = ui.view.resources.displayMetrics.density
        assertTrue(ui.view.measuredWidth <= 205 * density)
        assertTrue(ui.view.measuredHeight <= 205 * density)
        assertTrue(ui.find("screen-share-reply-scroll").measuredHeight <= 89 * density)
        val text = ui.find("screen-share-reply") as TextView
        assertEquals(reply, text.text.toString())
        assertTrue(text.textSize <= 15.1f * density)
        assertEquals(1, (ui.find("screen-share-input") as EditText).maxLines)
        assertEquals(5, (ui.find("screen-share-controls") as LinearLayout).childCount)
    }

    @Test fun fiveControlsDispatchSeparatelyAndMicrophoneIsIndependentOfSpeechOutput() = onMain {
        val called = mutableListOf<String>()
        val ui = create(called = called)
        ui.render(ready().copy(microphoneEnabled = false, speechOutputEnabled = true))
        ui.find("screen-share-mic").performClick()
        assertEquals(listOf("microphone"), called)
        assertTrue(ui.find("screen-share-mic").contentDescription.contains("开启自己的麦克风"))
        assertTrue(ui.find("screen-share-output").contentDescription.contains("不关闭自己的麦克风"))
        ui.find("screen-share-output").performClick()
        ui.find("screen-share-screen").performClick()
        ui.find("screen-share-stop").performClick()
        ui.find("screen-share-interval").performClick()
        assertEquals(listOf("microphone", "speech", "screen", "stop", "interval"), called)
    }

    @Test fun statusIsNotFalselyGreenAndAuthorizationLossNeedsAHumanReconnectClick() = onMain {
        val called = mutableListOf<String>()
        val ui = create(called = called)
        ui.render(ready("消息正文"))
        ui.find("screen-share-status").performClick()
        assertTrue(called.isEmpty())
        val green = (ui.find("screen-share-status-dot").background as GradientDrawable).color!!.defaultColor
        assertEquals(Color.rgb(108, 224, 148), green)
        ui.render(ready("消息正文").copy(health = ScreenShareHealth.ERROR, healthText = "暂未连接", canRetry = true))
        assertEquals("消息正文", (ui.find("screen-share-reply") as TextView).text.toString())
        assertNotEquals(green, (ui.find("screen-share-status-dot").background as GradientDrawable).color!!.defaultColor)
        ui.find("screen-share-status").performClick()
        ui.renderDisconnected()
        assertEquals(listOf("retry"), called)
        assertFalse(ui.find("screen-share-send").isEnabled)
        assertFalse(ui.find("screen-share-mic").isEnabled)
        ui.find("screen-share-status").performClick()
        assertEquals(listOf("retry", "reconnect"), called)
        ui.render(ready().copy(health = ScreenShareHealth.AUTHORIZATION_LOST, canRetry = false, sessionActive = false))
        ui.find("screen-share-status").performClick()
        assertEquals(listOf("retry", "reconnect", "reconnect"), called)
    }

    @Test fun minimizedTabCanExpandWithoutEnablingCaptureOrMicrophone() = onMain {
        val called = mutableListOf<String>()
        val ui = create(called = called)
        ui.render(ready()); ui.find("screen-share-collapse").performClick(); ui.layout(collapsed = true)
        assertEquals(View.GONE, ui.view.getChildAt(0).visibility)
        assertEquals(View.VISIBLE, ui.find("screen-share-expand").visibility)
        assertTrue(ui.view.width <= 63 * ui.view.resources.displayMetrics.density)
        ui.find("screen-share-expand").performClick()
        assertEquals(listOf("collapse", "expand"), called)
        assertNull(ui.currentBoundsOnScreen()) // No live overlay/capture permission requested by test.
    }

    @Test fun failedSendKeepsDraftAndLateSuccessCannotEraseNewTyping() = onMain {
        val called = mutableListOf<String>()
        val ui = create(called = called)
        ui.render(ready()); ui.input.setText("第一条")
        ui.find("screen-share-send").performClick(); ui.find("screen-share-send").performClick()
        assertEquals(listOf("send:第一条"), called)
        ui.finishSending("第一条", false)
        assertEquals("第一条", ui.input.text.toString())
        ui.find("screen-share-send").performClick()
        ui.input.setText("新草稿")
        ui.finishSending("第一条", true)
        assertEquals("新草稿", ui.input.text.toString())
    }
}
