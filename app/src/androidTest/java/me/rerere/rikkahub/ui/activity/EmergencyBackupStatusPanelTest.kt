package me.rerere.rikkahub.ui.activity

import android.app.Application
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import me.rerere.rikkahub.data.ai.IsolatedGenerationLoopRunner
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Views only: never starts EmergencyBackupActivity, acquires a lease, opens data or kills a process. */
@RunWith(AndroidJUnit4::class)
class EmergencyBackupStatusPanelTest {
    private fun panel(block: (EmergencyBackupStatusPanel, MutableList<String>) -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        check(instrumentation is IsolatedGenerationLoopRunner)
        assertEquals(Application::class.java, instrumentation.targetContext.applicationContext.javaClass)
        instrumentation.runOnMainSync {
            val copied = mutableListOf<String>()
            block(EmergencyBackupStatusPanel(instrumentation.targetContext) { copied += it }, copied)
        }
    }

    @Test fun unknownTotalsAnimateAndKnownByteTotalsShowOnlyStagePercentage() = panel { view, _ ->
        view.showProgress(emergencyBackupProgressPresentation("scan", 0, 0, 274))
        assertTrue(view.indicator.isIndeterminate)
        assertEquals(View.VISIBLE, view.indicator.visibility)
        assertTrue(view.summary.text.contains("274 项"))
        view.showProgress(emergencyBackupProgressPresentation("readback", 5, 10))
        assertFalse(view.indicator.isIndeterminate)
        assertEquals(50, view.indicator.progress)
        assertTrue(view.summary.text.startsWith("本阶段"))
    }

    @Test fun failureIsVisibleWithoutScrollingAndCopiesOnlyTheSafeCode() = panel { view, copied ->
        val code = "ORBIS-RESCUE/E_PATH_POLICY stage=scan"
        view.showMessage("文件名安全检查未通过。请保留原数据。", failed = true, diagnostic = code)
        assertTrue(view.heading.text.contains("操作未完成"))
        assertEquals(View.GONE, view.indicator.visibility)
        assertEquals(View.VISIBLE, view.errorCode.visibility)
        assertEquals(View.VISIBLE, view.copyButton.visibility)
        view.copyButton.performClick()
        assertEquals(listOf(code), copied)
        view.stopProgress()
        assertEquals(code, view.errorCode.text.toString())
        assertEquals(View.VISIBLE, view.copyButton.visibility)
    }

    @Test fun newExplicitAttemptClearsOldFailureAndNeverCopiesAStaleCode() = panel { view, copied ->
        view.showMessage("失败", failed = true, diagnostic = "ORBIS-RESCUE/E_UNCLASSIFIED stage=scan")
        view.showProgress(emergencyBackupProgressPresentation("copy", 0, 10))
        assertEquals(View.GONE, view.errorCode.visibility)
        assertEquals(View.GONE, view.copyButton.visibility)
        view.copyButton.performClick()
        assertTrue(copied.isEmpty())
    }

    @Test fun rebuiltFailureRemainsCopyableAndBusyRebuildDoesNotAnimateOrClaimSuccess() = panel { view, copied ->
        val code = "ORBIS-RESCUE/E_INTEGRITY stage=readback"
        val failure = emergencyBackupRebuiltPresentation(false, false, emergencyBackupSavedDiagnostic(code))
        view.showMessage(failure.message, failed = failure.failed, diagnostic = failure.diagnostic)
        view.copyButton.performClick()
        assertEquals(listOf(code), copied)
        val busy = emergencyBackupRebuiltPresentation(true, false, code)
        view.showMessage(busy.message, failed = busy.failed, diagnostic = busy.diagnostic)
        assertEquals(View.GONE, view.indicator.visibility)
        assertEquals(View.GONE, view.copyButton.visibility)
        assertTrue(view.summary.text.contains("无法在此确认上一操作结果"))
    }

    @Test fun statusPanelStaysAboveTheScrollableActionsOnAShortViewport() = panel { view, _ ->
        val context = view.context
        val page = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        page.addView(view, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val scroll = ScrollView(context).apply {
            addView(TextView(context).apply { text = "可滚动说明\n".repeat(100) })
        }
        page.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        view.showMessage("未能保存；不要卸载或清数据。", failed = true, diagnostic = "ORBIS-RESCUE/E_INTEGRITY stage=readback")
        val density = context.resources.displayMetrics.density
        val width = (360 * density).toInt()
        val height = (520 * density).toInt()
        page.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        page.layout(0, 0, width, height)
        scroll.scrollTo(0, (1000 * density).toInt())
        assertEquals(0, view.top)
        assertTrue(view.bottom <= scroll.top)
        assertTrue(scroll.height > 0)
        assertEquals(View.VISIBLE, view.copyButton.visibility)
        assertTrue(view.scrollHint.text.contains("滚动"))
    }
}
