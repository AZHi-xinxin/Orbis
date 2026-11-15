package me.rerere.rikkahub.ui.pages.orbis

import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.test.platform.app.InstrumentationRegistry
import me.rerere.rikkahub.data.model.OrbisCloudHomeConfig
import me.rerere.rikkahub.testutil.createShellComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Production WebView/bridge and native confirmation; no remote websites or personal books. */
class GardenLibraryExportDeviceTest {
    @get:Rule val compose = createShellComposeRule()
    private val ins = InstrumentationRegistry.getInstrumentation()
    private fun views(v:View):Sequence<View> = sequence {
        yield(v);if(v is ViewGroup) for(i in 0 until v.childCount)yieldAll(views(v.getChildAt(i)))
    }
    private fun js(v:WebView,script:String):String {
        val latch=CountDownLatch(1);var result=""
        ins.runOnMainSync { v.evaluateJavascript(script){result=it;latch.countDown()} }
        assertTrue(latch.await(5,TimeUnit.SECONDS));return result
    }
    @Test fun backupButtonsStayVisibleAndNativeCancelAllowsAnotherExport() {
        check(ins.targetContext.packageName=="org.orbis.agent.bodylab")
        compose.setContent { MaterialTheme { OrbisGardenWeb(OrbisCloudHomeConfig(), {}, {}) } }
        lateinit var web:WebView
        compose.waitUntil(10_000) {
            var found:WebView?=null
            ins.runOnMainSync {found=views(compose.activity.window.decorView).filterIsInstance<WebView>().firstOrNull()}
            if(found!=null)web=found!!;found!=null
        }
        compose.waitUntil(10_000) {js(web,"typeof window.GardenLibrary==='object'")=="true"}
        js(web,"window.GardenLibrary.open();void 0")
        compose.waitUntil(10_000) {js(web,"!!document.querySelector('[aria-label=\"导出或合并导入藏书阁备份\"]')")=="true"}
        repeat(2) {
            js(web,"document.querySelector('[aria-label=\"导出或合并导入藏书阁备份\"]').click();void 0")
            assertEquals("true",js(web,"(()=>{const s=document.querySelector('[data-library-backup]');const r=s.getBoundingClientRect();return r.height>100&&r.width>200&&!document.querySelector('[data-library-overlay]')})()"))
            js(web,"[...document.querySelectorAll('[data-library-backup] button')].find(x=>x.textContent==='导出备份').click();void 0")
            compose.waitUntil(10_000) {compose.onAllNodesWithText("导出藏书阁备份？").fetchSemanticsNodes().isNotEmpty()}
            compose.onNodeWithText("选择保存位置").assertIsDisplayed()
            compose.onNodeWithText("取消").performClick()
            compose.onNodeWithText("导出藏书阁备份？").assertDoesNotExist()
        }
        assertEquals("true",js(web,"!document.querySelector('[data-library-overlay]')&&!document.querySelector('[data-library-backup]')"))
    }
}
