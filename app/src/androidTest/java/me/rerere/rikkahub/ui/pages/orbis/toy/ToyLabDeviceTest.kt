package me.rerere.rikkahub.ui.pages.orbis.toy

import android.bluetooth.BluetoothAdapter
import android.graphics.Bitmap
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.*
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.orbis.toy.lab.*
import me.rerere.rikkahub.testutil.createShellComposeRule
import me.rerere.rikkahub.ui.pages.orbis.OrbisVisualTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Synthetic archives only; no scan, GATT connection, microphone, model or physical device writes. */
@RunWith(AndroidJUnit4::class)
class ToyLabDeviceTest {
    @get:Rule val compose = createShellComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun fixture(): ToyLabController {
        val c = ToyLabController(context)
        val report = LabDeviceReport("合成测试设备",listOf(LabChannel("0000ffe0-0000-1000-8000-00805f9b34fb","0000ffe1-0000-1000-8000-00805f9b34fb",12)),"TEST","1")
        val archive = LabArchive(report = report).addProfile(labCandidates(report).single(),"synthetic",1)
        @Suppress("UNCHECKED_CAST")
        val flow = ToyLabController::class.java.getDeclaredField("mutable").apply { isAccessible = true }.get(c) as MutableStateFlow<LabState>
        flow.value = LabState(archive = archive,message = "合成只读检查，未连接任何实物")
        return c
    }
    private fun save(name: String) {
        val image = compose.onRoot().captureToImage().asAndroidBitmap()
        File(context.getExternalFilesDir(null),name).outputStream().use { image.compress(Bitmap.CompressFormat.PNG,100,it) }
    }
    @Test fun emptyPageHasIndependentTabsAndAlwaysReachableStop() {
        val c = ToyLabController(context)
        compose.setContent { MaterialTheme { OrbisVisualTheme { ToyLabScreen(c,"synthetic","测试助手",{}) } } }
        compose.onNodeWithTag("toy_lab_stop").assertIsDisplayed()
        compose.onNodeWithTag("toy_lab_tab_1").performClick()
        compose.onNodeWithText("先到“设备”页选择自己的设备并检查通道。").assertIsDisplayed()
        compose.onNodeWithTag("toy_lab_content").performScrollToIndex(0)
        compose.onNodeWithTag("toy_lab_tab_2").performClick()
        compose.onNodeWithText("导入档案").performScrollTo().assertIsNotEnabled()
        assertFalse(c.state.value.scanning); assertFalse(c.state.value.connected)
        compose.onNodeWithTag("toy_lab_tab_0").performClick()
        save("toy-lab-dev138-empty.png")
    }
    @Test fun designShowsRealSyntheticArchiveAndDisabledRunWithoutConnection() {
        val c = fixture()
        compose.setContent { MaterialTheme { OrbisVisualTheme { ToyLabScreen(c,"synthetic","测试助手",{}) } } }
        compose.onNodeWithTag("toy_lab_tab_1").performClick()
        compose.onNodeWithTag("toy_lab_content").performScrollToNode(hasText("开始试调"))
        compose.onNodeWithText("开始试调").performScrollTo().assertIsNotEnabled()
        save("toy-lab-dev138-trial.png")
        compose.onNodeWithTag("toy_lab_content").performScrollToIndex(0)
        compose.onNodeWithTag("toy_lab_tab_2").performClick()
        compose.onNodeWithText("还没有保存观察记录。未观察的结果不会自动填入。").assertExists()
        compose.onNodeWithTag("toy_lab_content").performScrollToNode(hasTestTag("toy_lab_share"))
        compose.onNodeWithTag("toy_lab_share").performScrollTo().assertIsOff()
        save("toy-lab-dev138-archive.png")
        assertFalse(c.state.value.scanning)
    }
    @Test fun largeDarkTextStillReachesGrantAndStop() {
        val c = fixture()
        compose.setContent { MaterialTheme { OrbisVisualTheme(darkTheme = true) {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density,1.5f)) {
                ToyLabScreen(c,"synthetic","一个比较长的测试助手名称",{})
            }
        } } }
        compose.onNodeWithTag("toy_lab_content").performScrollToIndex(0)
        compose.onNodeWithTag("toy_lab_tab_2").performClick()
        compose.onNodeWithTag("toy_lab_content").performScrollToNode(hasTestTag("toy_lab_share"))
        compose.onNodeWithTag("toy_lab_share").performScrollTo().assertIsOff()
        compose.onNodeWithTag("toy_lab_stop").assertIsDisplayed()
        save("toy-lab-dev138-dark-large.png")
    }
    @Test fun oldEmergencyStopCannotTouchAReplacementDevice() = runBlocking {
        val c = fixture()
        val device = BluetoothAdapter.getDefaultAdapter().getRemoteDevice("02:00:00:00:00:01")
        ToyLabController::class.java.getDeclaredField("selected").apply { isAccessible = true }.set(c,device)
        c.share("owner-a")
        val oldStop = buildToyLabTools(context,"owner-a",c).single { it.name == "toy_lab_stop" }
        val previous = c.selectionScope()
        // Exercise the production selection transition only. No scan/connect/write occurs.
        withContext(Dispatchers.Main) {
            ToyLabController::class.java.getDeclaredMethod("replaceSelectedDevice",android.bluetooth.BluetoothDevice::class.java)
                .apply { isAccessible = true }.invoke(c,device)
        }
        assertNotEquals(previous,c.selectionScope())
        val reply = oldStop.execute(buildJsonObject {}).toString()
        assertTrue(reply.contains("false")); assertTrue(reply.contains("未执行"))
        Unit
    }
    @Test fun deviceGrantIsOwnerScopedImportCannotGrantAndOldToolStopsAfterRevocation() = runBlocking {
        val c = fixture()
        assertTrue(buildToyLabTools(context,"owner-a",c).isEmpty())
        // Android object identity only; getRemoteDevice does not connect or scan.
        val device = BluetoothAdapter.getDefaultAdapter().getRemoteDevice("02:00:00:00:00:01")
        ToyLabController::class.java.getDeclaredField("selected").apply { isAccessible = true }.set(c,device)
        c.share("owner-a")
        assertTrue(buildToyLabTools(context,"owner-b",c).isEmpty())
        val tools = buildToyLabTools(context,"owner-a",c)
        val tool = tools.single { it.name == "toy_lab" }
        assertTrue(tool.isApprovalCurrent())
        val data = (tool.execute(buildJsonObject { put("action","status") }).single() as UIMessagePart.Text).text
        assertTrue(data.contains("合成测试设备")); assertFalse(data.contains("02:00:00"))
        val archive = c.state.value.archive!!
        val installed = tool.execute(buildJsonObject { put("action","install"); put("expected_revision",1)
            put("profile",ToyLabJson.encodeToJsonElement(archive.latest()!!.profile.copy(name = "AI修改方案"))) }).toString()
        assertTrue(installed.contains("true")); assertEquals(2,c.state.value.archive!!.latest()!!.revision)
        assertFalse(c.state.value.connected)
        c.share(null)
        assertFalse(tool.isApprovalCurrent())
        val stale = tool.execute(buildJsonObject { put("action","status") }).toString()
        assertFalse(stale.contains("合成测试设备")); assertTrue(stale.contains("false"))
        c.share("owner-a")
        val exported = c.exportArchive()
        assertFalse(exported.contains("owner-a")); assertFalse(exported.contains("02:00:00"))
        c.importArchive(exported)
        assertNull(c.state.value.sharedWith)
        assertTrue(buildToyLabTools(context,"owner-a",c).isEmpty())
        // Remove only archives created by this synthetic controller.
        c.state.value.archiveId?.let { File(context.noBackupFilesDir,"orbis-toy-lab/$it.json").delete() }
        Unit
    }
}
